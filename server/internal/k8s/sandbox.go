// Package-level sandbox gateway, the Go counterpart of
// infrastructure/kubernetes/sandbox.
package k8s

import (
	"bufio"
	"context"
	"fmt"
	"regexp"
	"sort"
	"strings"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/util/intstr"

	batchv1 "k8s.io/api/batch/v1"
)

const (
	sandboxLabelType       = "oops.type"
	sandboxLabelTypeValue  = "sandbox"
	sandboxLabelID         = "oops.sandbox.id"
	sandboxLabelKind       = "oops.sandbox.kind"
	sandboxKindEphemeral   = "ephemeral"
	sandboxKindPersistent  = "persistent"
	sandboxLabelCreatedBy  = "oops.sandbox.created-by"
	sandboxLabelImage      = "oops.sandbox.image"
	sandboxAnnotationName  = "oops.sandbox.name"
	sandboxAnnotationImage = "oops.sandbox.image"
	sandboxNamePrefix      = "oops-sandbox-"
	sandboxContainerName   = "sandbox"

	sandboxKeepaliveCommand = "trap : TERM INT; sleep infinity & wait"
	alpineMateKey           = "alpine-mate"
	alpineMateImage         = "linuxserver/webtop:alpine-mate"
)

var (
	labelValueInvalid = regexp.MustCompile(`[^A-Za-z0-9._-]`)
	labelValueEdge    = regexp.MustCompile(`^[^A-Za-z0-9]+|[^A-Za-z0-9]+$`)
)

func sanitizeLabelValue(value string) string {
	replaced := labelValueInvalid.ReplaceAllString(value, "_")
	if len(replaced) > 63 {
		replaced = replaced[:63]
	}
	return labelValueEdge.ReplaceAllString(replaced, "")
}

// SandboxJobSpec / PersistentSandboxSpec mirror the Java records.
type SandboxJobSpec struct {
	Image, Command                                   string
	TimeoutSeconds, TTLSecondsAfterFinished          int
	CPURequest, CPULimit, MemoryRequest, MemoryLimit string
	Env                                              map[string]string
	CreatedByUserID                                  string
}

type PersistentSandboxSpec struct {
	SandboxID, Name, Image                           string
	CPURequest, CPULimit, MemoryRequest, MemoryLimit string
	Env                                              map[string]string
	CreatedByUserID                                  string
	UseDefaultKeepalive                              bool
}

// SandboxInstanceView mirrors SandboxInstance.
type SandboxInstanceView struct {
	ID            string  `json:"id"`
	Name          string  `json:"name"`
	Environment   string  `json:"environment"`
	Image         string  `json:"image"`
	Status        string  `json:"status"`
	CreatedBy     string  `json:"createdBy"`
	CreatedByName *string `json:"createdByName"`
	CreatedAt     *string `json:"createdAt"`
	CPURequest    *string `json:"cpuRequest"`
	CPULimit      *string `json:"cpuLimit"`
	MemoryRequest *string `json:"memoryRequest"`
	MemoryLimit   *string `json:"memoryLimit"`
}

func toEnvVars(env map[string]string) []corev1.EnvVar {
	names := make([]string, 0, len(env))
	for name := range env {
		names = append(names, name)
	}
	sort.Strings(names)
	variables := make([]corev1.EnvVar, 0, len(env))
	for _, name := range names {
		variables = append(variables, corev1.EnvVar{Name: name, Value: env[name]})
	}
	return variables
}

func sandboxResources(cpuRequest, cpuLimit, memoryRequest, memoryLimit string, memorySuffix string) corev1.ResourceRequirements {
	requests, limits := corev1.ResourceList{}, corev1.ResourceList{}
	if cpuRequest != "" {
		requests[corev1.ResourceCPU] = resource.MustParse(cpuRequest)
	}
	if memoryRequest != "" {
		requests[corev1.ResourceMemory] = resource.MustParse(memoryRequest + memorySuffix)
	}
	if cpuLimit != "" {
		limits[corev1.ResourceCPU] = resource.MustParse(cpuLimit)
	}
	if memoryLimit != "" {
		limits[corev1.ResourceMemory] = resource.MustParse(memoryLimit + memorySuffix)
	}
	return corev1.ResourceRequirements{Requests: requests, Limits: limits}
}

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

// CreatePersistentSandbox builds either the alpine-mate builtin (webtop
// StatefulSet + Service) or a generic custom StatefulSet.
func CreatePersistentSandbox(ctx context.Context, cluster *Cluster, workNamespace, environmentName string, spec *PersistentSandboxSpec) (*SandboxInstanceView, error) {
	statefulSetName := sandboxNamePrefix + spec.SandboxID
	imageLabel := spec.Image
	if spec.Image == alpineMateKey {
		imageLabel = alpineMateKey
	}
	labels := map[string]string{
		sandboxLabelType:  sandboxLabelTypeValue,
		sandboxLabelKind:  sandboxKindPersistent,
		sandboxLabelID:    spec.SandboxID,
		sandboxLabelImage: sanitizeLabelValue(imageLabel),
	}
	if spec.CreatedByUserID != "" {
		labels[sandboxLabelCreatedBy] = spec.CreatedByUserID
	}
	annotations := map[string]string{
		sandboxAnnotationName:  spec.Name,
		sandboxAnnotationImage: spec.Image,
	}
	setAnnotation := func(name, value string) {
		if value != "" {
			annotations[name] = value
		}
	}
	setAnnotation("oops.sandbox.cpu-request", spec.CPURequest)
	setAnnotation("oops.sandbox.cpu-limit", spec.CPULimit)
	setAnnotation("oops.sandbox.memory-request", spec.MemoryRequest)
	setAnnotation("oops.sandbox.memory-limit", spec.MemoryLimit)

	var statefulSet *appsv1.StatefulSet
	var service *corev1.Service
	privileged := true
	replicas := int32(1)

	if spec.Image == alpineMateKey {
		selector := map[string]string{sandboxLabelID: spec.SandboxID}
		env := map[string]string{
			"PUID": "1000", "PGID": "1000", "TZ": "Asia/Shanghai", "SUBFOLDER": "/", "TITLE": spec.Name,
		}
		for name, value := range spec.Env {
			env[name] = value
		}
		shmLimit := resource.MustParse("1Gi")
		statefulSet = &appsv1.StatefulSet{
			ObjectMeta: metav1.ObjectMeta{
				Name: statefulSetName, Namespace: workNamespace, Labels: labels, Annotations: annotations,
			},
			Spec: appsv1.StatefulSetSpec{
				ServiceName: statefulSetName,
				Replicas:    &replicas,
				Selector:    &metav1.LabelSelector{MatchLabels: selector},
				Template: corev1.PodTemplateSpec{
					ObjectMeta: metav1.ObjectMeta{Labels: labels, Annotations: annotations},
					Spec: corev1.PodSpec{
						RestartPolicy: corev1.RestartPolicyAlways,
						Containers: []corev1.Container{{
							Name:            sandboxContainerName,
							Image:           alpineMateImage,
							ImagePullPolicy: corev1.PullAlways,
							SecurityContext: &corev1.SecurityContext{
								Privileged:     &privileged,
								SeccompProfile: &corev1.SeccompProfile{Type: corev1.SeccompProfileTypeUnconfined},
							},
							Env: toEnvVars(env),
							Ports: []corev1.ContainerPort{
								{Name: "http", ContainerPort: 3000, Protocol: corev1.ProtocolTCP},
								{Name: "https", ContainerPort: 3001, Protocol: corev1.ProtocolTCP},
							},
							Resources: sandboxResources(spec.CPURequest, spec.CPULimit, spec.MemoryRequest, spec.MemoryLimit, "Mi"),
							VolumeMounts: []corev1.VolumeMount{
								{Name: "config", MountPath: "/config"},
								{Name: "dshm", MountPath: "/dev/shm"},
							},
						}},
						Volumes: []corev1.Volume{
							{Name: "config", VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{}}},
							{Name: "dshm", VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{
								Medium: corev1.StorageMediumMemory, SizeLimit: &shmLimit,
							}}},
						},
						ImagePullSecrets: []corev1.LocalObjectReference{{Name: "dockerhub"}},
					},
				},
			},
		}
		service = &corev1.Service{
			ObjectMeta: metav1.ObjectMeta{Name: statefulSetName, Namespace: workNamespace},
			Spec: corev1.ServiceSpec{
				Type:            corev1.ServiceTypeClusterIP,
				SessionAffinity: corev1.ServiceAffinityNone,
				Selector:        selector,
				Ports: []corev1.ServicePort{
					{Name: "http", Protocol: corev1.ProtocolTCP, Port: 3000, TargetPort: intstr.FromInt(3000)},
					{Name: "https", Protocol: corev1.ProtocolTCP, Port: 3001, TargetPort: intstr.FromInt(3001)},
				},
			},
		}
	} else {
		container := corev1.Container{
			Name:            sandboxContainerName,
			Image:           spec.Image,
			ImagePullPolicy: corev1.PullAlways,
			Env:             toEnvVars(spec.Env),
			Resources:       sandboxResources(spec.CPURequest, spec.CPULimit, spec.MemoryRequest, spec.MemoryLimit, "Mi"),
			SecurityContext: &corev1.SecurityContext{Privileged: &privileged},
		}
		if spec.UseDefaultKeepalive {
			container.Command = []string{"/bin/sh", "-c", sandboxKeepaliveCommand}
		} else {
			container.Stdin, container.TTY = true, true
		}
		statefulSet = &appsv1.StatefulSet{
			ObjectMeta: metav1.ObjectMeta{
				Name: statefulSetName, Namespace: workNamespace, Labels: labels, Annotations: annotations,
			},
			Spec: appsv1.StatefulSetSpec{
				ServiceName: statefulSetName,
				Replicas:    &replicas,
				Selector:    &metav1.LabelSelector{MatchLabels: labels},
				Template: corev1.PodTemplateSpec{
					ObjectMeta: metav1.ObjectMeta{Labels: labels, Annotations: annotations},
					Spec: corev1.PodSpec{
						RestartPolicy:    corev1.RestartPolicyAlways,
						Containers:       []corev1.Container{container},
						ImagePullSecrets: []corev1.LocalObjectReference{{Name: "dockerhub"}},
					},
				},
			},
		}
	}

	created, err := cluster.Clientset.AppsV1().StatefulSets(workNamespace).Create(ctx, statefulSet, metav1.CreateOptions{})
	if err != nil {
		return nil, fmt.Errorf("failed to create sandbox: %w", err)
	}
	if service != nil {
		block := true
		service.OwnerReferences = []metav1.OwnerReference{{
			APIVersion: "apps/v1", Kind: "StatefulSet", Name: statefulSetName,
			UID: created.UID, BlockOwnerDeletion: &block,
		}}
		if _, err := cluster.Clientset.CoreV1().Services(workNamespace).Create(ctx, service, metav1.CreateOptions{}); err != nil {
			return nil, fmt.Errorf("failed to create sandbox: %w", err)
		}
	}
	pod, _ := cluster.Clientset.CoreV1().Pods(workNamespace).Get(ctx, statefulSetName+"-0", metav1.GetOptions{})
	view := toSandboxInstanceView(environmentName, created, pod)
	return &view, nil
}

func toSandboxInstanceView(environmentName string, statefulSet *appsv1.StatefulSet, pod *corev1.Pod) SandboxInstanceView {
	labels, annotations := statefulSet.Labels, statefulSet.Annotations
	view := SandboxInstanceView{
		ID:          labels[sandboxLabelID],
		Name:        annotations[sandboxAnnotationName],
		Environment: environmentName,
		Image:       annotations[sandboxAnnotationImage],
		Status:      deriveSandboxStatus(statefulSet, pod),
		CreatedBy:   labels[sandboxLabelCreatedBy],
	}
	if !statefulSet.CreationTimestamp.IsZero() {
		createdAt := statefulSet.CreationTimestamp.UTC().Format(time.RFC3339)
		view.CreatedAt = &createdAt
	}
	annotationPointer := func(name string) *string {
		if value, present := annotations[name]; present {
			return &value
		}
		return nil
	}
	view.CPURequest = annotationPointer("oops.sandbox.cpu-request")
	view.CPULimit = annotationPointer("oops.sandbox.cpu-limit")
	view.MemoryRequest = annotationPointer("oops.sandbox.memory-request")
	view.MemoryLimit = annotationPointer("oops.sandbox.memory-limit")
	return view
}

func deriveSandboxStatus(statefulSet *appsv1.StatefulSet, pod *corev1.Pod) string {
	if statefulSet.DeletionTimestamp != nil {
		return "TERMINATING"
	}
	if pod == nil || len(pod.Status.ContainerStatuses) == 0 {
		return "PENDING"
	}
	allReady := true
	for _, status := range pod.Status.ContainerStatuses {
		if !status.Ready {
			allReady = false
		}
	}
	if allReady {
		return "RUNNING"
	}
	for _, status := range pod.Status.ContainerStatuses {
		if waiting := status.State.Waiting; waiting != nil {
			switch waiting.Reason {
			case "CrashLoopBackOff", "ImagePullBackOff", "ErrImagePull", "CreateContainerConfigError":
				return "FAILED"
			}
		}
	}
	return "PENDING"
}

// ListPersistentSandboxes mirrors listPersistent.
func ListPersistentSandboxes(ctx context.Context, cluster *Cluster, workNamespace, environmentName, createdByUserID, image string) ([]SandboxInstanceView, error) {
	selector := sandboxLabelType + "=" + sandboxLabelTypeValue + "," + sandboxLabelKind + "=" + sandboxKindPersistent
	if createdByUserID != "" {
		selector += "," + sandboxLabelCreatedBy + "=" + createdByUserID
	}
	if image != "" {
		selector += "," + sandboxLabelImage + "=" + sanitizeLabelValue(image)
	}
	statefulSets, err := cluster.Clientset.AppsV1().StatefulSets(workNamespace).List(ctx, metav1.ListOptions{
		LabelSelector: selector,
	})
	if err != nil {
		return nil, err
	}
	views := []SandboxInstanceView{}
	for i := range statefulSets.Items {
		statefulSet := &statefulSets.Items[i]
		var pod *corev1.Pod
		if sandboxID := statefulSet.Labels[sandboxLabelID]; sandboxID != "" {
			pod, _ = cluster.Clientset.CoreV1().Pods(workNamespace).Get(ctx, sandboxNamePrefix+sandboxID+"-0", metav1.GetOptions{})
		}
		views = append(views, toSandboxInstanceView(environmentName, statefulSet, pod))
	}
	sort.SliceStable(views, func(i, j int) bool {
		left, right := "", ""
		if views[i].CreatedAt != nil {
			left = *views[i].CreatedAt
		}
		if views[j].CreatedAt != nil {
			right = *views[j].CreatedAt
		}
		return left > right
	})
	return views, nil
}

// FindPersistentSandbox mirrors findPersistent.
func FindPersistentSandbox(ctx context.Context, cluster *Cluster, workNamespace, environmentName, sandboxID string) (*SandboxInstanceView, error) {
	statefulSet, err := cluster.Clientset.AppsV1().StatefulSets(workNamespace).Get(ctx, sandboxNamePrefix+sandboxID, metav1.GetOptions{})
	if apierrors.IsNotFound(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if statefulSet.Labels[sandboxLabelKind] != sandboxKindPersistent {
		return nil, nil
	}
	pod, _ := cluster.Clientset.CoreV1().Pods(workNamespace).Get(ctx, sandboxNamePrefix+sandboxID+"-0", metav1.GetOptions{})
	view := toSandboxInstanceView(environmentName, statefulSet, pod)
	return &view, nil
}

func DeletePersistentSandbox(ctx context.Context, cluster *Cluster, workNamespace, sandboxID string) error {
	err := cluster.Clientset.AppsV1().StatefulSets(workNamespace).
		Delete(ctx, sandboxNamePrefix+sandboxID, metav1.DeleteOptions{})
	if apierrors.IsNotFound(err) {
		return nil
	}
	return err
}

// ExecSandboxInstance runs a command in the persistent sandbox pod, streaming
// each combined-output line to emit and returning the exit code.
func ExecSandboxInstance(ctx context.Context, cluster *Cluster, workNamespace, sandboxID, command string, timeoutSeconds int, emit func(line string) error) (int, error) {
	podName := sandboxNamePrefix + sandboxID + "-0"
	writer := &lineEmitWriter{emit: emit}
	execContext, cancel := context.WithTimeout(ctx, time.Duration(timeoutSeconds)*time.Second)
	defer cancel()
	stdout, stderr, err := execRaw(execContext, cluster, workNamespace, podName, sandboxContainerName,
		[]string{"/bin/sh", "-c", command}, writer)
	writer.flush()
	_ = stdout
	_ = stderr
	if execContext.Err() == context.DeadlineExceeded {
		return -1, fmt.Errorf("Sandbox exec timed out")
	}
	if err != nil {
		// A non-zero exit surfaces as an exec error carrying the code.
		if code, isExit := exitCodeFromError(err); isExit {
			return code, nil
		}
		return -1, err
	}
	return 0, nil
}

type lineEmitWriter struct {
	emit    func(string) error
	pending strings.Builder
}

func (writer *lineEmitWriter) Write(data []byte) (int, error) {
	for _, character := range string(data) {
		if character == '\n' {
			if err := writer.emit(writer.pending.String()); err != nil {
				return 0, err
			}
			writer.pending.Reset()
		} else {
			writer.pending.WriteRune(character)
		}
	}
	return len(data), nil
}

func (writer *lineEmitWriter) flush() {
	if writer.pending.Len() > 0 {
		_ = writer.emit(writer.pending.String())
		writer.pending.Reset()
	}
}
