package sandbox

import (
	"context"
	"fmt"
	"log/slog"
	"sort"
	"strings"
	"time"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/k8s"
	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/apimachinery/pkg/util/intstr"
	"k8s.io/client-go/kubernetes"
)

const createFailurePrefix = "Failed to create sandbox: "

var failedWaitingReasons = map[string]bool{
	"CrashLoopBackOff":           true,
	"ImagePullBackOff":           true,
	"ErrImagePull":               true,
	"CreateContainerConfigError": true,
}

// buildPersistentLabels: imageLabel is the builtin key for builtin runtimes
// and the raw image reference otherwise.
func buildPersistentLabels(spec PersistentSpec, imageLabel string) map[string]string {
	labels := map[string]string{
		LabelType:      TypeValue,
		LabelKind:      KindPersistent,
		LabelSandboxID: spec.SandboxID,
		LabelImage:     SanitizeLabelValue(imageLabel),
	}
	if !isBlank(spec.CreatedByUserID) {
		labels[LabelCreatedBy] = spec.CreatedByUserID
	}
	return labels
}

func buildPersistentAnnotations(spec PersistentSpec) map[string]string {
	annotations := map[string]string{
		AnnotationName:  spec.Name,
		AnnotationImage: spec.Image,
	}
	putIfPresent(annotations, AnnotationCPURequest, spec.CPURequest)
	putIfPresent(annotations, AnnotationCPULimit, spec.CPULimit)
	putIfPresent(annotations, AnnotationMemoryRequest, spec.MemoryRequest)
	putIfPresent(annotations, AnnotationMemoryLimit, spec.MemoryLimit)
	return annotations
}

func putIfPresent(target map[string]string, key string, value *string) {
	if value != nil {
		target[key] = *value
	}
}

// withMemoryUnit appends "Mi" verbatim, as the Java template did — "512Mi"
// becomes "512MiMi". Kept for parity; the caller is expected to pass a number.
func withMemoryUnit(value string) string { return value + "Mi" }

// buildPersistentResources: cpu as given, memory + "Mi", each only when present.
func buildPersistentResources(spec PersistentSpec) (corev1.ResourceRequirements, error) {
	requirements := corev1.ResourceRequirements{}
	entries := []struct {
		list  *corev1.ResourceList
		name  corev1.ResourceName
		value *string
		unit  func(string) string
	}{
		{&requirements.Requests, corev1.ResourceCPU, spec.CPURequest, nil},
		{&requirements.Requests, corev1.ResourceMemory, spec.MemoryRequest, withMemoryUnit},
		{&requirements.Limits, corev1.ResourceCPU, spec.CPULimit, nil},
		{&requirements.Limits, corev1.ResourceMemory, spec.MemoryLimit, withMemoryUnit},
	}
	for _, entry := range entries {
		if domain.IsBlank(entry.value) {
			continue
		}
		raw := *entry.value
		if entry.unit != nil {
			raw = entry.unit(raw)
		}
		quantity, err := resource.ParseQuantity(raw)
		if err != nil {
			return requirements, fmt.Errorf("invalid %s quantity %q: %w", entry.name, raw, err)
		}
		ensureList(entry.list)[entry.name] = quantity
	}
	return requirements, nil
}

func ensureList(list *corev1.ResourceList) corev1.ResourceList {
	if *list == nil {
		*list = corev1.ResourceList{}
	}
	return *list
}

// buildPersistentStatefulSet is the custom-image StatefulSet (§3.7). Note that
// no headless Service accompanies it; serviceName is set for parity only.
func buildPersistentStatefulSet(spec PersistentSpec, workNamespace string) (*appsv1.StatefulSet, error) {
	resources, err := buildPersistentResources(spec)
	if err != nil {
		return nil, err
	}
	labels := buildPersistentLabels(spec, spec.Image)
	annotations := buildPersistentAnnotations(spec)
	name := resourceName(spec.SandboxID)

	container := corev1.Container{
		Name:            ContainerName,
		Image:           spec.Image,
		ImagePullPolicy: corev1.PullAlways,
		Env:             toCoreEnv(spec.Env),
		Resources:       resources,
		SecurityContext: &corev1.SecurityContext{Privileged: domain.Ptr(true)},
	}
	if spec.UseDefaultKeepalive {
		container.Command = []string{binSh, "-c", persistentKeepaliveCommand}
	} else {
		container.Stdin = true
		container.TTY = true
	}

	return &appsv1.StatefulSet{
		TypeMeta: metav1.TypeMeta{APIVersion: "apps/v1", Kind: "StatefulSet"},
		ObjectMeta: metav1.ObjectMeta{
			Name:        name,
			Namespace:   workNamespace,
			Labels:      labels,
			Annotations: annotations,
		},
		Spec: appsv1.StatefulSetSpec{
			ServiceName: name,
			Replicas:    domain.Ptr(int32(1)),
			Selector:    &metav1.LabelSelector{MatchLabels: copyLabels(labels)},
			Template: corev1.PodTemplateSpec{
				ObjectMeta: metav1.ObjectMeta{Labels: copyLabels(labels), Annotations: copyLabels(annotations)},
				Spec: corev1.PodSpec{
					RestartPolicy:    corev1.RestartPolicyAlways,
					ImagePullSecrets: []corev1.LocalObjectReference{{Name: imagePullSecretName}},
					Containers:       []corev1.Container{container},
				},
			},
		},
	}, nil
}

// buildAlpineMateStatefulSet mirrors AlpineMateTemplate.buildStatefulSet.
func buildAlpineMateStatefulSet(spec PersistentSpec, workNamespace string) (*appsv1.StatefulSet, error) {
	resources, err := buildPersistentResources(spec)
	if err != nil {
		return nil, err
	}
	labels := buildPersistentLabels(spec, builtinAlpineMateKey)
	annotations := buildPersistentAnnotations(spec)
	name := resourceName(spec.SandboxID)

	return &appsv1.StatefulSet{
		TypeMeta: metav1.TypeMeta{APIVersion: "apps/v1", Kind: "StatefulSet"},
		ObjectMeta: metav1.ObjectMeta{
			Name:        name,
			Namespace:   workNamespace,
			Labels:      labels,
			Annotations: annotations,
		},
		Spec: appsv1.StatefulSetSpec{
			ServiceName: name,
			Replicas:    domain.Ptr(int32(1)),
			// Only the id label, unlike the custom-image selector.
			Selector: &metav1.LabelSelector{MatchLabels: map[string]string{LabelSandboxID: spec.SandboxID}},
			Template: corev1.PodTemplateSpec{
				ObjectMeta: metav1.ObjectMeta{Labels: copyLabels(labels), Annotations: copyLabels(annotations)},
				Spec: corev1.PodSpec{
					RestartPolicy:    corev1.RestartPolicyAlways,
					ImagePullSecrets: []corev1.LocalObjectReference{{Name: imagePullSecretName}},
					Containers: []corev1.Container{{
						Name:            ContainerName,
						Image:           builtinAlpineMateImage,
						ImagePullPolicy: corev1.PullAlways,
						SecurityContext: &corev1.SecurityContext{
							Privileged:     domain.Ptr(true),
							SeccompProfile: &corev1.SeccompProfile{Type: corev1.SeccompProfileTypeUnconfined},
						},
						Env: alpineMateEnv(spec),
						Ports: []corev1.ContainerPort{
							{Name: "http", ContainerPort: 3000, Protocol: corev1.ProtocolTCP},
							{Name: "https", ContainerPort: 3001, Protocol: corev1.ProtocolTCP},
						},
						Resources: resources,
						VolumeMounts: []corev1.VolumeMount{
							{Name: "config", MountPath: "/config"},
							{Name: "dshm", MountPath: "/dev/shm"},
						},
					}},
					Volumes: []corev1.Volume{
						{Name: "config", VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{}}},
						{Name: "dshm", VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{
							Medium:    corev1.StorageMediumMemory,
							SizeLimit: domain.Ptr(resource.MustParse("1Gi")),
						}}},
					},
				},
			},
		},
	}, nil
}

// alpineMateEnv: fixed defaults first, user env appended, and a user entry
// with the same name replaces the default in place (LinkedHashMap semantics).
func alpineMateEnv(spec PersistentSpec) []corev1.EnvVar {
	merged := []corev1.EnvVar{
		{Name: "PUID", Value: "1000"},
		{Name: "PGID", Value: "1000"},
		{Name: "TZ", Value: "Asia/Shanghai"},
		{Name: "SUBFOLDER", Value: "/"},
		{Name: "TITLE", Value: spec.Name},
	}
	for _, entry := range spec.Env {
		replaced := false
		for index := range merged {
			if merged[index].Name == entry.Name {
				merged[index].Value = entry.Value
				replaced = true
				break
			}
		}
		if !replaced {
			merged = append(merged, corev1.EnvVar{Name: entry.Name, Value: entry.Value})
		}
	}
	return merged
}

// buildAlpineMateService mirrors AlpineMateTemplate.buildService; the owner
// reference deliberately carries no controller flag.
func buildAlpineMateService(sandboxID, workNamespace string, ownerUID string) *corev1.Service {
	name := resourceName(sandboxID)
	return &corev1.Service{
		TypeMeta: metav1.TypeMeta{APIVersion: "v1", Kind: "Service"},
		ObjectMeta: metav1.ObjectMeta{
			Name:      name,
			Namespace: workNamespace,
			OwnerReferences: []metav1.OwnerReference{{
				APIVersion:         "apps/v1",
				Kind:               "StatefulSet",
				Name:               name,
				UID:                types.UID(ownerUID),
				BlockOwnerDeletion: domain.Ptr(true),
			}},
		},
		Spec: corev1.ServiceSpec{
			Type:            corev1.ServiceTypeClusterIP,
			SessionAffinity: corev1.ServiceAffinityNone,
			Selector:        map[string]string{LabelSandboxID: sandboxID},
			Ports: []corev1.ServicePort{
				{Name: "http", Protocol: corev1.ProtocolTCP, Port: 3000, TargetPort: intstr.FromInt32(3000)},
				{Name: "https", Protocol: corev1.ProtocolTCP, Port: 3001, TargetPort: intstr.FromInt32(3001)},
			},
		},
	}
}

// CreatePersistent creates the StatefulSet (and, for alpine-mate, its Service)
// and returns the freshly mapped instance. Failures are Biz errors with the
// Java message "Failed to create sandbox: <cause>".
func (g *Gateway) CreatePersistent(ctx context.Context, apiServer *domain.KubernetesApiServer, workNamespace string, env *domain.Environment, spec PersistentSpec) (*domain.SandboxInstance, error) {
	client, err := g.pool.Get(apiServer)
	if err != nil {
		return nil, domain.BizWrap(createFailurePrefix+err.Error(), err)
	}
	clientset := client.Clientset

	var created *appsv1.StatefulSet
	if IsBuiltin(spec.Image) {
		manifest, buildErr := buildAlpineMateStatefulSet(spec, workNamespace)
		if buildErr != nil {
			return nil, domain.BizWrap(createFailurePrefix+buildErr.Error(), buildErr)
		}
		created, err = clientset.AppsV1().StatefulSets(workNamespace).Create(ctx, manifest, metav1.CreateOptions{})
		if err != nil {
			slog.Warn("Failed to create builtin sandbox", "sandboxId", spec.SandboxID, "error", err)
			return nil, domain.BizWrap(createFailurePrefix+err.Error(), err)
		}
		service := buildAlpineMateService(spec.SandboxID, workNamespace, string(created.UID))
		if _, err := clientset.CoreV1().Services(workNamespace).Create(ctx, service, metav1.CreateOptions{}); err != nil {
			// The StatefulSet is deliberately not rolled back (parity with Java).
			slog.Warn("Failed to create builtin sandbox service", "sandboxId", spec.SandboxID, "error", err)
			return nil, domain.BizWrap(createFailurePrefix+err.Error(), err)
		}
	} else {
		manifest, buildErr := buildPersistentStatefulSet(spec, workNamespace)
		if buildErr != nil {
			return nil, domain.BizWrap(createFailurePrefix+buildErr.Error(), buildErr)
		}
		created, err = clientset.AppsV1().StatefulSets(workNamespace).Create(ctx, manifest, metav1.CreateOptions{})
		if err != nil {
			slog.Warn("Failed to create persistent sandbox", "sandboxId", spec.SandboxID, "error", err)
			return nil, domain.BizWrap(createFailurePrefix+err.Error(), err)
		}
	}

	pod := findPod(ctx, clientset, workNamespace, spec.SandboxID)
	instance := toSandboxInstance(env, created, pod)
	return &instance, nil
}

// ListPersistent lists persistent instances in the environment's work
// namespace, optionally narrowed by creator and image, newest first.
func (g *Gateway) ListPersistent(ctx context.Context, apiServer *domain.KubernetesApiServer, env *domain.Environment, createdBy, image string) ([]domain.SandboxInstance, error) {
	client, err := g.pool.Get(apiServer)
	if err != nil {
		return nil, k8s.TranslateError(err)
	}
	workNamespace := domain.Deref(env.WorkNamespace)

	selector := []string{LabelType + "=" + TypeValue, LabelKind + "=" + KindPersistent}
	if !isBlank(createdBy) {
		selector = append(selector, LabelCreatedBy+"="+createdBy)
	}
	if !isBlank(image) {
		selector = append(selector, LabelImage+"="+SanitizeLabelValue(image))
	}

	statefulSets, err := client.Clientset.AppsV1().StatefulSets(workNamespace).List(ctx, metav1.ListOptions{LabelSelector: strings.Join(selector, ",")})
	if err != nil {
		return nil, k8s.TranslateError(err)
	}

	instances := make([]domain.SandboxInstance, 0, len(statefulSets.Items))
	for index := range statefulSets.Items {
		statefulSet := &statefulSets.Items[index]
		pod := findPod(ctx, client.Clientset, workNamespace, statefulSet.Labels[LabelSandboxID])
		instances = append(instances, toSandboxInstance(env, statefulSet, pod))
	}
	sortByCreatedAtDesc(instances)
	return instances, nil
}

// FindPersistent returns nil when the StatefulSet is absent or not persistent.
func (g *Gateway) FindPersistent(ctx context.Context, apiServer *domain.KubernetesApiServer, env *domain.Environment, sandboxID string) (*domain.SandboxInstance, error) {
	client, err := g.pool.Get(apiServer)
	if err != nil {
		return nil, k8s.TranslateError(err)
	}
	workNamespace := domain.Deref(env.WorkNamespace)

	statefulSet, err := client.Clientset.AppsV1().StatefulSets(workNamespace).Get(ctx, resourceName(sandboxID), metav1.GetOptions{})
	if err != nil {
		if k8s.IsNotFound(err) {
			return nil, nil
		}
		return nil, k8s.TranslateError(err)
	}
	if statefulSet.Labels[LabelKind] != KindPersistent {
		return nil, nil
	}
	pod := findPod(ctx, client.Clientset, workNamespace, sandboxID)
	instance := toSandboxInstance(env, statefulSet, pod)
	return &instance, nil
}

// DeletePersistent deletes the StatefulSet; the Service (alpine-mate only)
// cascades through its ownerReference.
func (g *Gateway) DeletePersistent(ctx context.Context, apiServer *domain.KubernetesApiServer, workNamespace, sandboxID string) error {
	client, err := g.pool.Get(apiServer)
	if err != nil {
		return k8s.TranslateError(err)
	}
	err = client.Clientset.AppsV1().StatefulSets(workNamespace).Delete(ctx, resourceName(sandboxID), metav1.DeleteOptions{})
	if err != nil && !k8s.IsNotFound(err) {
		return k8s.TranslateError(err)
	}
	return nil
}

// findPod fetches oops-sandbox-<id>-0, tolerating its absence.
func findPod(ctx context.Context, clientset kubernetes.Interface, workNamespace, sandboxID string) *corev1.Pod {
	pod, err := clientset.CoreV1().Pods(workNamespace).Get(ctx, PodName(sandboxID), metav1.GetOptions{})
	if err != nil {
		return nil
	}
	return pod
}

// deriveStatus mirrors KubernetesSandboxExecutionGateway.deriveStatus.
func deriveStatus(statefulSet *appsv1.StatefulSet, pod *corev1.Pod) domain.SandboxInstanceStatus {
	if statefulSet != nil && statefulSet.DeletionTimestamp != nil {
		return domain.SandboxTerminating
	}
	if pod == nil || len(pod.Status.ContainerStatuses) == 0 {
		return domain.SandboxPending
	}
	allReady := true
	for _, status := range pod.Status.ContainerStatuses {
		if !status.Ready {
			allReady = false
			break
		}
	}
	if allReady {
		return domain.SandboxRunning
	}
	for _, status := range pod.Status.ContainerStatuses {
		if status.State.Waiting != nil && failedWaitingReasons[status.State.Waiting.Reason] {
			return domain.SandboxFailed
		}
	}
	return domain.SandboxPending
}

func toSandboxInstance(env *domain.Environment, statefulSet *appsv1.StatefulSet, pod *corev1.Pod) domain.SandboxInstance {
	labels := statefulSet.Labels
	annotations := statefulSet.Annotations
	instance := domain.SandboxInstance{
		ID:            labels[LabelSandboxID],
		Name:          lookup(annotations, AnnotationName),
		Image:         lookup(annotations, AnnotationImage),
		Status:        deriveStatus(statefulSet, pod),
		CreatedBy:     lookup(labels, LabelCreatedBy),
		CreatedAt:     formatCreatedAt(statefulSet.CreationTimestamp),
		CPURequest:    lookup(annotations, AnnotationCPURequest),
		CPULimit:      lookup(annotations, AnnotationCPULimit),
		MemoryRequest: lookup(annotations, AnnotationMemoryRequest),
		MemoryLimit:   lookup(annotations, AnnotationMemoryLimit),
	}
	if env != nil {
		instance.Environment = env.Name
	}
	return instance
}

func lookup(source map[string]string, key string) *string {
	if source == nil {
		return nil
	}
	value, ok := source[key]
	if !ok {
		return nil
	}
	return &value
}

// formatCreatedAt renders creationTimestamp as a Java Instant would serialize.
func formatCreatedAt(timestamp metav1.Time) *string {
	if timestamp.IsZero() {
		return nil
	}
	return domain.Ptr(timestamp.UTC().Format(time.RFC3339))
}

func sortByCreatedAtDesc(instances []domain.SandboxInstance) {
	sort.SliceStable(instances, func(left, right int) bool {
		leftTime, leftOK := parseCreatedAt(instances[left].CreatedAt)
		rightTime, rightOK := parseCreatedAt(instances[right].CreatedAt)
		switch {
		case leftOK && rightOK:
			return leftTime.After(rightTime)
		case leftOK:
			return true // nulls last
		default:
			return false
		}
	})
}

func parseCreatedAt(value *string) (time.Time, bool) {
	if value == nil {
		return time.Time{}, false
	}
	parsed, err := time.Parse(time.RFC3339, *value)
	return parsed, err == nil
}
