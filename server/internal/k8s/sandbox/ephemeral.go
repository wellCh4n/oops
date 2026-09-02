package sandbox

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"strings"
	"time"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/k8s"
	batchv1 "k8s.io/api/batch/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/util/wait"
	"k8s.io/client-go/kubernetes"
)

const (
	podAppearTimeout       = 2 * time.Minute
	containerStartTimeout  = 2 * time.Minute
	finalTerminationWait   = time.Minute
	pollInterval           = 500 * time.Millisecond
	exitCodeUnknown        = -1
	jobNameLabel           = "job-name"
	executionFailurePrefix = "Sandbox execution failed: "
)

// buildJob mirrors KubernetesSandboxExecutionGateway.buildJob (§3.6).
func buildJob(spec JobSpec, sandboxID, workNamespace string) (*batchv1.Job, error) {
	requests, err := parseResourceList(spec.CPURequest, spec.MemoryRequest)
	if err != nil {
		return nil, err
	}
	limits, err := parseResourceList(spec.CPULimit, spec.MemoryLimit)
	if err != nil {
		return nil, err
	}
	labels := map[string]string{
		LabelType:      TypeValue,
		LabelKind:      KindEphemeral,
		LabelSandboxID: sandboxID,
	}
	if !isBlank(spec.CreatedByUserID) {
		labels[LabelCreatedBy] = spec.CreatedByUserID
	}
	activeDeadline := int64(spec.TimeoutSeconds)
	ttl := int32(spec.TTLSecondsAfterFinished)
	backoffLimit := int32(0)

	return &batchv1.Job{
		TypeMeta: metav1.TypeMeta{APIVersion: "batch/v1", Kind: "Job"},
		ObjectMeta: metav1.ObjectMeta{
			Name:      resourceName(sandboxID),
			Namespace: workNamespace,
			Labels:    labels,
		},
		Spec: batchv1.JobSpec{
			BackoffLimit:            &backoffLimit,
			ActiveDeadlineSeconds:   &activeDeadline,
			TTLSecondsAfterFinished: &ttl,
			Template: corev1.PodTemplateSpec{
				ObjectMeta: metav1.ObjectMeta{Labels: copyLabels(labels)},
				Spec: corev1.PodSpec{
					RestartPolicy:    corev1.RestartPolicyNever,
					ImagePullSecrets: []corev1.LocalObjectReference{{Name: imagePullSecretName}},
					Containers: []corev1.Container{{
						Name:            ContainerName,
						Image:           spec.Image,
						ImagePullPolicy: corev1.PullAlways,
						Command:         []string{binSh, "-c", spec.Script},
						Env:             toCoreEnv(spec.Env),
						Resources:       corev1.ResourceRequirements{Requests: requests, Limits: limits},
						SecurityContext: &corev1.SecurityContext{Privileged: domain.Ptr(true)},
					}},
				},
			},
		},
	}, nil
}

func parseResourceList(cpu, memory string) (corev1.ResourceList, error) {
	cpuQuantity, err := resource.ParseQuantity(cpu)
	if err != nil {
		return nil, fmt.Errorf("invalid cpu quantity %q: %w", cpu, err)
	}
	memoryQuantity, err := resource.ParseQuantity(memory)
	if err != nil {
		return nil, fmt.Errorf("invalid memory quantity %q: %w", memory, err)
	}
	return corev1.ResourceList{corev1.ResourceCPU: cpuQuantity, corev1.ResourceMemory: memoryQuantity}, nil
}

func copyLabels(labels map[string]string) map[string]string {
	copied := make(map[string]string, len(labels))
	for key, value := range labels {
		copied[key] = value
	}
	return copied
}

func toCoreEnv(env []EnvVar) []corev1.EnvVar {
	if len(env) == 0 {
		return nil
	}
	converted := make([]corev1.EnvVar, 0, len(env))
	for _, entry := range env {
		converted = append(converted, corev1.EnvVar{Name: entry.Name, Value: entry.Value})
	}
	return converted
}

// Execute runs the script to completion and returns its output and exit code.
// Failures are plain (non-Biz) errors, as the Java gateway threw a
// RuntimeException that the controller rendered as "Internal server error".
func (g *Gateway) Execute(ctx context.Context, apiServer *domain.KubernetesApiServer, workNamespace string, spec JobSpec) (ExecResult, error) {
	sandboxID := domain.NewID()
	var output strings.Builder
	exitCode, err := g.run(ctx, apiServer, workNamespace, spec, sandboxID, func(line string) {
		output.WriteString(line)
		output.WriteString("\n")
	})
	if err != nil {
		slog.Warn("Sandbox execution ended abnormally", "sandboxId", sandboxID, "error", err)
		return ExecResult{}, fmt.Errorf("%s%w", executionFailurePrefix, err)
	}
	return ExecResult{ExitCode: exitCode, Output: output.String()}, nil
}

// Stream runs the script, handing each log line to onLine as it arrives and
// the exit code to onExit once the container has terminated. The returned
// error is the raw failure for the SSE layer to complete with.
func (g *Gateway) Stream(ctx context.Context, apiServer *domain.KubernetesApiServer, workNamespace string, spec JobSpec, onLine func(string), onExit func(code int)) error {
	sandboxID := domain.NewID()
	exitCode, err := g.run(ctx, apiServer, workNamespace, spec, sandboxID, onLine)
	if err != nil {
		slog.Warn("Sandbox stream ended abnormally", "sandboxId", sandboxID, "error", err)
		return err
	}
	if onExit != nil {
		onExit(exitCode)
	}
	return nil
}

// run creates the Job, follows the pod log and returns the exit code once the
// container has terminated. A dedicated streaming client is used because the
// log follow legitimately outlives the pooled client's request timeout.
func (g *Gateway) run(ctx context.Context, apiServer *domain.KubernetesApiServer, workNamespace string, spec JobSpec, sandboxID string, onLine func(string)) (int, error) {
	client, err := k8s.StreamingClient(apiServer)
	if err != nil {
		return exitCodeUnknown, err
	}
	clientset := client.Clientset

	job, err := buildJob(spec, sandboxID, workNamespace)
	if err != nil {
		return exitCodeUnknown, err
	}
	podName, err := launchJobAndWaitForPod(ctx, clientset, workNamespace, job)
	if err != nil {
		return exitCodeUnknown, err
	}

	if err := followLog(ctx, clientset, workNamespace, podName, time.Duration(spec.TimeoutSeconds)*time.Second, onLine); err != nil {
		return exitCodeUnknown, err
	}

	// K8s may keep follow=true open a little after exit; give the status a
	// minute to settle before reading the exit code.
	pod, err := waitForPod(ctx, clientset, workNamespace, podName, finalTerminationWait, allContainersTerminated)
	if err != nil && pod == nil {
		return exitCodeUnknown, err
	}
	return readExitCode(pod), nil
}

func launchJobAndWaitForPod(ctx context.Context, clientset kubernetes.Interface, workNamespace string, job *batchv1.Job) (string, error) {
	created, err := clientset.BatchV1().Jobs(workNamespace).Create(ctx, job, metav1.CreateOptions{})
	if err != nil {
		return "", err
	}
	jobName := created.Name

	var podName string
	err = wait.PollUntilContextTimeout(ctx, pollInterval, podAppearTimeout, true, func(ctx context.Context) (bool, error) {
		pods, listErr := clientset.CoreV1().Pods(workNamespace).List(ctx, metav1.ListOptions{LabelSelector: jobNameLabel + "=" + jobName})
		if listErr != nil {
			return false, listErr
		}
		if len(pods.Items) == 0 {
			return false, nil
		}
		podName = pods.Items[0].Name
		return true, nil
	})
	if err != nil {
		return "", fmt.Errorf("waiting for pod of job %s: %w", jobName, err)
	}

	if _, err := waitForPod(ctx, clientset, workNamespace, podName, containerStartTimeout, anyContainerRunningOrTerminated); err != nil {
		return "", fmt.Errorf("waiting for container of pod %s to start: %w", podName, err)
	}
	return podName, nil
}

// waitForPod polls the pod until predicate holds or timeout elapses. The last
// observed pod is returned even on timeout so callers can still read status.
func waitForPod(ctx context.Context, clientset kubernetes.Interface, namespace, podName string, timeout time.Duration, predicate func(*corev1.Pod) bool) (*corev1.Pod, error) {
	var latest *corev1.Pod
	err := wait.PollUntilContextTimeout(ctx, pollInterval, timeout, true, func(ctx context.Context) (bool, error) {
		pod, getErr := clientset.CoreV1().Pods(namespace).Get(ctx, podName, metav1.GetOptions{})
		if getErr != nil {
			return false, getErr
		}
		latest = pod
		return predicate(pod), nil
	})
	return latest, err
}

func anyContainerRunningOrTerminated(pod *corev1.Pod) bool {
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

// followLog streams the pod log line by line. A companion goroutine closes the
// stream once every container has terminated (or timeout passes), because the
// API server may keep a follow open after the process exited.
func followLog(ctx context.Context, clientset kubernetes.Interface, namespace, podName string, timeout time.Duration, onLine func(string)) error {
	stream, err := clientset.CoreV1().Pods(namespace).GetLogs(podName, &corev1.PodLogOptions{Follow: true}).Stream(ctx)
	if err != nil {
		return err
	}
	defer stream.Close()

	closeCtx, cancelClose := context.WithCancel(ctx)
	defer cancelClose()
	go func() {
		_, _ = waitForPod(closeCtx, clientset, namespace, podName, timeout, allContainersTerminated)
		stream.Close()
	}()

	scanner := bufio.NewScanner(stream)
	scanner.Buffer(make([]byte, 64*1024), 4*1024*1024)
	for scanner.Scan() {
		if onLine != nil {
			onLine(scanner.Text())
		}
	}
	// Read errors after a forced close are expected and swallowed, as Java did.
	if scanErr := scanner.Err(); scanErr != nil && !errors.Is(scanErr, io.EOF) {
		slog.Debug("sandbox log stream ended", "pod", podName, "error", scanErr)
	}
	return nil
}

// readExitCode returns the first container's terminated exit code, else -1.
func readExitCode(pod *corev1.Pod) int {
	if pod == nil || len(pod.Status.ContainerStatuses) == 0 {
		return exitCodeUnknown
	}
	terminated := pod.Status.ContainerStatuses[0].State.Terminated
	if terminated == nil {
		return exitCodeUnknown
	}
	return int(terminated.ExitCode)
}
