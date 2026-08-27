package k8s

import (
	"bufio"
	"context"
	"fmt"
	"time"

	batchv1 "k8s.io/api/batch/v1"
	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// Ephemeral sandbox executions: one Job per run, logs streamed line by line.

// RunSandboxJob creates the ephemeral Job, waits for its pod, streams the log
// lines to emit, then reports the exit code — the shared core of the streaming
// and blocking execution paths.
func RunSandboxJob(ctx context.Context, cluster *Cluster, workNamespace, sandboxID string, spec *SandboxJobSpec, emit func(line string) error) (int, error) {
	jobName := sandboxNamePrefix + sandboxID
	labels := map[string]string{
		sandboxLabelType: sandboxLabelTypeValue,
		sandboxLabelKind: sandboxKindEphemeral,
		sandboxLabelID:   sandboxID,
	}
	if spec.CreatedByUserID != "" {
		labels[sandboxLabelCreatedBy] = spec.CreatedByUserID
	}
	backoffLimit := int32(0)
	activeDeadline := int64(spec.TimeoutSeconds)
	ttl := int32(spec.TTLSecondsAfterFinished)
	privileged := true
	job := &batchv1.Job{
		ObjectMeta: metav1.ObjectMeta{Name: jobName, Namespace: workNamespace, Labels: labels},
		Spec: batchv1.JobSpec{
			BackoffLimit:            &backoffLimit,
			ActiveDeadlineSeconds:   &activeDeadline,
			TTLSecondsAfterFinished: &ttl,
			Template: corev1.PodTemplateSpec{
				ObjectMeta: metav1.ObjectMeta{Labels: labels},
				Spec: corev1.PodSpec{
					RestartPolicy: corev1.RestartPolicyNever,
					Containers: []corev1.Container{{
						Name:            sandboxContainerName,
						Image:           spec.Image,
						ImagePullPolicy: corev1.PullAlways,
						Command:         []string{"/bin/sh", "-c", spec.Command},
						Env:             toEnvVars(spec.Env),
						Resources:       sandboxResources(spec.CPURequest, spec.CPULimit, spec.MemoryRequest, spec.MemoryLimit, ""),
						SecurityContext: &corev1.SecurityContext{Privileged: &privileged},
					}},
					ImagePullSecrets: []corev1.LocalObjectReference{{Name: "dockerhub"}},
				},
			},
		},
	}
	if _, err := cluster.Clientset.BatchV1().Jobs(workNamespace).Create(ctx, job, metav1.CreateOptions{}); err != nil {
		return -1, err
	}

	podName, err := waitForJobPod(ctx, cluster, workNamespace, jobName)
	if err != nil {
		return -1, err
	}
	follow := true
	logStream, err := cluster.Clientset.CoreV1().Pods(workNamespace).
		GetLogs(podName, &corev1.PodLogOptions{Follow: follow}).Stream(ctx)
	if err == nil {
		scanner := bufio.NewScanner(logStream)
		scanner.Buffer(make([]byte, 64*1024), 1024*1024)
		for scanner.Scan() {
			if emit(scanner.Text()) != nil {
				break
			}
		}
		logStream.Close()
	}
	terminated, err := waitForPodCondition(ctx, cluster, workNamespace, podName, time.Minute, allContainersTerminated)
	if err != nil || terminated == nil {
		return -1, fmt.Errorf("sandbox pod did not terminate: %v", err)
	}
	return readExitCode(terminated), nil
}

func waitForJobPod(ctx context.Context, cluster *Cluster, workNamespace, jobName string) (string, error) {
	deadline := time.Now().Add(2 * time.Minute)
	for time.Now().Before(deadline) {
		pods, err := cluster.Clientset.CoreV1().Pods(workNamespace).List(ctx, metav1.ListOptions{
			LabelSelector: "job-name=" + jobName,
		})
		if err == nil && len(pods.Items) > 0 {
			podName := pods.Items[0].Name
			pod, err := waitForPodCondition(ctx, cluster, workNamespace, podName, time.Until(deadline), containerRunningOrTerminated)
			if err != nil {
				return "", err
			}
			if pod != nil {
				return podName, nil
			}
		}
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		case <-time.After(time.Second):
		}
	}
	return "", fmt.Errorf("sandbox pod did not start in time")
}

func waitForPodCondition(ctx context.Context, cluster *Cluster, namespace, podName string, timeout time.Duration, condition func(*corev1.Pod) bool) (*corev1.Pod, error) {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		pod, err := cluster.Clientset.CoreV1().Pods(namespace).Get(ctx, podName, metav1.GetOptions{})
		if err != nil && !apierrors.IsNotFound(err) {
			return nil, err
		}
		if err == nil && condition(pod) {
			return pod, nil
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(time.Second):
		}
	}
	return nil, nil
}

func containerRunningOrTerminated(pod *corev1.Pod) bool {
	for _, status := range pod.Status.ContainerStatuses {
		if status.State.Running != nil || status.State.Terminated != nil {
			return true
		}
	}
	return false
}

func allContainersTerminated(pod *corev1.Pod) bool {
	if len(pod.Status.ContainerStatuses) == 0 {
		return false
	}
	for _, status := range pod.Status.ContainerStatuses {
		if status.State.Terminated == nil {
			return false
		}
	}
	return true
}

func readExitCode(pod *corev1.Pod) int {
	for _, status := range pod.Status.ContainerStatuses {
		if status.State.Terminated != nil {
			return int(status.State.Terminated.ExitCode)
		}
	}
	return -1
}
