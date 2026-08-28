package engine

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	schedulingv1 "k8s.io/api/scheduling/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/util/intstr"

	"github.com/wellch4n/oops/server/internal/k8s"
	"github.com/wellch4n/oops/server/internal/store"
)

func priorityClassNameOf(priority *string) (string, int32) {
	if priority == nil {
		return "", 0
	}
	switch strings.ToUpper(*priority) {
	case "HIGH":
		return "oops-high-priority", 1_000_000
	case "LOW":
		return "oops-low-priority", -1_000_000
	}
	return "", 0
}

func processPriorityClass(ctx context.Context, cluster *k8s.Cluster, input *deployInput) error {
	var priority *string
	if input.ExpertConfig != nil {
		priority = input.ExpertConfig.Priority
	}
	name, value := priorityClassNameOf(priority)
	if name == "" {
		return nil
	}
	_, err := cluster.Clientset.SchedulingV1().PriorityClasses().Get(ctx, name, metav1.GetOptions{})
	if err == nil {
		return nil // pre-existing object (possibly admin-tuned) is left untouched
	}
	if !apierrors.IsNotFound(err) {
		return err
	}
	_, err = cluster.Clientset.SchedulingV1().PriorityClasses().Create(ctx, &schedulingv1.PriorityClass{
		ObjectMeta: metav1.ObjectMeta{Name: name},
		Value:      value,
	}, metav1.CreateOptions{})
	if apierrors.IsAlreadyExists(err) {
		return nil
	}
	return err
}

func distinctInternalPorts(serviceConfig *store.ServiceConfigView) []int {
	seen := map[int]struct{}{}
	ports := []int{}
	if serviceConfig == nil {
		return ports
	}
	for _, port := range serviceConfig.InternalPorts {
		if _, duplicate := seen[port]; duplicate {
			continue
		}
		seen[port] = struct{}{}
		ports = append(ports, port)
	}
	return ports
}

func probeEnabled(probe *store.Probe) bool {
	return probe != nil && probe.Enabled != nil && *probe.Enabled
}

func probeFor(probe *store.Probe, port int) *corev1.Probe {
	path := "/"
	if probe.Path != nil && *probe.Path != "" {
		path = *probe.Path
	}
	intOr := func(value *int, fallback int32) int32 {
		if value != nil {
			return int32(*value)
		}
		return fallback
	}
	return &corev1.Probe{
		ProbeHandler: corev1.ProbeHandler{
			HTTPGet: &corev1.HTTPGetAction{Path: path, Port: intstr.FromInt(port)},
		},
		InitialDelaySeconds: intOr(probe.InitialDelaySeconds, 30),
		PeriodSeconds:       intOr(probe.PeriodSeconds, 10),
		TimeoutSeconds:      intOr(probe.TimeoutSeconds, 3),
		FailureThreshold:    intOr(probe.FailureThreshold, 3),
	}
}

func processStatefulSet(ctx context.Context, cluster *k8s.Cluster, input *deployInput) (*metav1.OwnerReference, error) {
	labels := applicationLabels(input.Application)
	artifact := ""
	if input.Pipeline.Artifact != nil {
		artifact = *input.Pipeline.Artifact
	}
	container := corev1.Container{
		Name:            input.Application,
		Image:           artifact,
		ImagePullPolicy: corev1.PullIfNotPresent,
	}

	runtimeSpec := input.RuntimeSpec
	if runtimeSpec == nil {
		runtimeSpec = &store.RuntimeEnvironmentConfig{}
	}
	requests, limits := corev1.ResourceList{}, corev1.ResourceList{}
	setQuantity := func(list corev1.ResourceList, name corev1.ResourceName, value *string, suffix string) {
		if value != nil && *value != "" {
			list[name] = resource.MustParse(*value + suffix)
		}
	}
	setQuantity(requests, corev1.ResourceCPU, runtimeSpec.CPURequest, "")
	setQuantity(limits, corev1.ResourceCPU, runtimeSpec.CPULimit, "")
	setQuantity(requests, corev1.ResourceMemory, runtimeSpec.MemoryRequest, "Mi")
	setQuantity(limits, corev1.ResourceMemory, runtimeSpec.MemoryLimit, "Mi")
	if len(requests) > 0 || len(limits) > 0 {
		container.Resources = corev1.ResourceRequirements{Requests: requests, Limits: limits}
	}

	var applicationPort *int
	if input.ServiceConfig != nil {
		applicationPort = input.ServiceConfig.Port
	}
	if applicationPort != nil && *applicationPort > 0 {
		container.Ports = append(container.Ports, corev1.ContainerPort{Name: "http", ContainerPort: int32(*applicationPort)})
	}
	for _, internalPort := range distinctInternalPorts(input.ServiceConfig) {
		if applicationPort != nil && internalPort == *applicationPort {
			continue
		}
		if internalPort == deployServicePort {
			continue
		}
		container.Ports = append(container.Ports, corev1.ContainerPort{
			Name: fmt.Sprintf("tcp-%d", internalPort), ContainerPort: int32(internalPort),
		})
	}
	if input.HealthCheck != nil && applicationPort != nil && *applicationPort > 0 {
		if probeEnabled(input.HealthCheck.Liveness) {
			container.LivenessProbe = probeFor(input.HealthCheck.Liveness, *applicationPort)
		}
		if probeEnabled(input.HealthCheck.Readiness) {
			container.ReadinessProbe = probeFor(input.HealthCheck.Readiness, *applicationPort)
		}
	}

	optional := true
	container.EnvFrom = []corev1.EnvFromSource{
		{ConfigMapRef: &corev1.ConfigMapEnvSource{
			LocalObjectReference: corev1.LocalObjectReference{Name: input.Application}, Optional: &optional}},
		{SecretRef: &corev1.SecretEnvSource{
			LocalObjectReference: corev1.LocalObjectReference{Name: input.Application}, Optional: &optional}},
	}
	volumes := appendFileMounts(ctx, cluster, input.Namespace, input.Application, &container)

	replicas := int32(0)
	if runtimeSpec.Replicas != nil {
		replicas = int32(*runtimeSpec.Replicas)
	}
	annotations := map[string]string{rolloutStartedAtAnnotation: time.Now().UTC().Format(time.RFC3339)}
	if input.Pipeline.ID != "" {
		annotations[pipelineIDAnnotation] = input.Pipeline.ID
	}

	enableServiceLinks := false
	podSpec := corev1.PodSpec{
		EnableServiceLinks: &enableServiceLinks,
		Containers:         []corev1.Container{container},
		ImagePullSecrets:   []corev1.LocalObjectReference{{Name: defaultImagePullSecret}},
		Volumes:            volumes,
	}
	if input.ExpertConfig != nil {
		if input.ExpertConfig.ServiceAccountName != nil && *input.ExpertConfig.ServiceAccountName != "" {
			podSpec.ServiceAccountName = *input.ExpertConfig.ServiceAccountName
		}
		if name, _ := priorityClassNameOf(input.ExpertConfig.Priority); name != "" {
			podSpec.PriorityClassName = name
		}
		if affinity := nodeAffinityFor(input.ExpertConfig.NodeNames); affinity != nil {
			podSpec.Affinity = affinity
		}
	}

	statefulSet := &appsv1.StatefulSet{
		TypeMeta: metav1.TypeMeta{APIVersion: statefulSetAPIVersion, Kind: statefulSetKind},
		ObjectMeta: metav1.ObjectMeta{
			Name: input.Application, Namespace: input.Namespace,
			Labels: labels, Annotations: annotations,
		},
		Spec: appsv1.StatefulSetSpec{
			Replicas:    &replicas,
			ServiceName: input.Application,
			Selector:    &metav1.LabelSelector{MatchLabels: labels},
			Template: corev1.PodTemplateSpec{
				ObjectMeta: metav1.ObjectMeta{Labels: labels},
				Spec:       podSpec,
			},
		},
	}
	if err := serverSideApply(ctx, cluster, statefulSet,
		schema.GroupVersionResource{Group: "apps", Version: "v1", Resource: "statefulsets"},
		input.Namespace, input.Application); err != nil {
		return nil, err
	}
	created, err := cluster.Clientset.AppsV1().StatefulSets(input.Namespace).Get(ctx, input.Application, metav1.GetOptions{})
	if err != nil {
		return nil, err
	}
	controller, block := true, true
	return &metav1.OwnerReference{
		APIVersion: statefulSetAPIVersion, Kind: statefulSetKind,
		Name: input.Application, UID: created.UID,
		Controller: &controller, BlockOwnerDeletion: &block,
	}, nil
}

// appendFileMounts mirrors StatefulSetProcessor.appendConfigInjection's file half.
func appendFileMounts(ctx context.Context, cluster *k8s.Cluster, namespace, applicationName string, container *corev1.Container) []corev1.Volume {
	volumes := []corev1.Volume{}
	filesName := applicationName + ".files"
	readOnly := true

	fileNameOf := func(mountPath string) string {
		trimmed := strings.TrimRight(strings.TrimSpace(mountPath), "/")
		if slash := strings.LastIndex(trimmed, "/"); slash >= 0 {
			return trimmed[slash+1:]
		}
		return trimmed
	}
	addMount := func(volumeName, key, mountPath string, source corev1.VolumeSource) {
		volumes = append(volumes, corev1.Volume{Name: volumeName, VolumeSource: source})
		container.VolumeMounts = append(container.VolumeMounts, corev1.VolumeMount{
			Name: volumeName, MountPath: mountPath, SubPath: fileNameOf(mountPath), ReadOnly: readOnly,
		})
	}
	readMountsAnnotation := func(annotations map[string]string) map[string]string {
		mounts := map[string]string{}
		if raw, found := annotations["oops.mounts"]; found {
			_ = json.Unmarshal([]byte(raw), &mounts)
		}
		return mounts
	}

	if configMap, err := cluster.Clientset.CoreV1().ConfigMaps(namespace).Get(ctx, filesName, metav1.GetOptions{}); err == nil {
		mounts := readMountsAnnotation(configMap.Annotations)
		for key := range configMap.Data {
			mountPath := mounts[key]
			if mountPath == "" {
				continue
			}
			volumeName := fmt.Sprintf("config-%s-%d", k8s.ResourceNameOf(key), len(volumes))
			addMount(volumeName, key, mountPath, corev1.VolumeSource{
				ConfigMap: &corev1.ConfigMapVolumeSource{
					LocalObjectReference: corev1.LocalObjectReference{Name: filesName},
					Items:                []corev1.KeyToPath{{Key: key, Path: fileNameOf(mountPath)}},
				},
			})
		}
	}
	if secret, err := cluster.Clientset.CoreV1().Secrets(namespace).Get(ctx, filesName, metav1.GetOptions{}); err == nil {
		mounts := readMountsAnnotation(secret.Annotations)
		for key := range secret.Data {
			mountPath := mounts[key]
			if mountPath == "" {
				continue
			}
			volumeName := fmt.Sprintf("secret-%s-%d", k8s.ResourceNameOf(key), len(volumes))
			addMount(volumeName, key, mountPath, corev1.VolumeSource{
				Secret: &corev1.SecretVolumeSource{
					SecretName: filesName,
					Items:      []corev1.KeyToPath{{Key: key, Path: fileNameOf(mountPath)}},
				},
			})
		}
	}
	return volumes
}

func nodeAffinityFor(nodeNames []string) *corev1.Affinity {
	filtered := []string{}
	for _, name := range nodeNames {
		if strings.TrimSpace(name) != "" {
			filtered = append(filtered, strings.TrimSpace(name))
		}
	}
	if len(filtered) == 0 {
		return nil
	}
	return &corev1.Affinity{
		NodeAffinity: &corev1.NodeAffinity{
			RequiredDuringSchedulingIgnoredDuringExecution: &corev1.NodeSelector{
				NodeSelectorTerms: []corev1.NodeSelectorTerm{{
					MatchFields: []corev1.NodeSelectorRequirement{{
						Key: "metadata.name", Operator: corev1.NodeSelectorOpIn, Values: filtered,
					}},
				}},
			},
		},
	}
}
