package k8s

import (
	"context"
	"fmt"
	"sort"
	"strings"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/util/intstr"
)

// Persistent sandbox instances: long-lived StatefulSets (alpine-mate builtin
// or a custom image) with exec, files and terminal access.

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
		return -1, fmt.Errorf("sandbox exec timed out")
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
