package k8s

import (
	"context"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/wellch4n/oops/server/internal/domain"
	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/util/intstr"
	"k8s.io/client-go/kubernetes"
)

// statefulSetProcessor applies the StatefulSet, records the owner reference
// and unsticks a blocked rollout.
func statefulSetProcessor(ctx context.Context, deploy *deployContext) error {
	slog.Info("Checking stateful set for application", "namespace", deploy.namespace(), "application", deploy.appName())
	volumes, mounts, err := configFileProjection(ctx, deploy.client.Clientset, deploy.namespace(), deploy.appName())
	if err != nil {
		return err
	}
	statefulSet := BuildStatefulSet(StatefulSetSpecInput{
		ApplicationName: deploy.appName(),
		Image:           domain.Deref(deploy.Pipeline.Artifact),
		PipelineID:      deploy.Pipeline.ID,
		RuntimeSpec:     deploy.RuntimeSpec,
		HealthCheck:     deploy.HealthCheck,
		ServiceConfig:   deploy.ServiceConfig,
		ExpertConfig:    deploy.ExpertConfig,
		Volumes:         volumes,
		VolumeMounts:    mounts,
		Now:             time.Now(),
	})
	patch, err := applyPatch(statefulSet)
	if err != nil {
		return err
	}
	applied, err := deploy.client.Clientset.AppsV1().StatefulSets(deploy.namespace()).Patch(ctx, deploy.appName(), applyPatchType, patch, forceApply)
	if err != nil {
		return err
	}
	reference := StatefulSetOwnerReference(deploy.appName(), applied.UID)
	deploy.ownerRef = &reference
	DeleteRolloutBlockingPods(ctx, deploy.client, deploy.namespace(), deploy.labels)
	return nil
}

// StatefulSetSpecInput is the pure input of BuildStatefulSet.
type StatefulSetSpecInput struct {
	ApplicationName string
	Image           string
	PipelineID      string
	RuntimeSpec     domain.RuntimeEnvironmentConfig
	HealthCheck     *domain.HealthCheck
	ServiceConfig   *domain.ApplicationServiceConfig
	ExpertConfig    domain.ExpertEnvironmentConfig
	Volumes         []corev1.Volume
	VolumeMounts    []corev1.VolumeMount
	Now             time.Time
}

// BuildStatefulSet constructs the StatefulSet manifest of spec-deploy §1.6.
func BuildStatefulSet(in StatefulSetSpecInput) *appsv1.StatefulSet {
	labels := ApplicationLabels(in.ApplicationName)
	annotations := map[string]string{RolloutStartedAtAnnotation: formatInstant(in.Now)}
	if !isBlank(in.PipelineID) {
		annotations[PipelineIDAnnotation] = in.PipelineID
	}

	container := corev1.Container{
		Name:            in.ApplicationName,
		Image:           in.Image,
		ImagePullPolicy: corev1.PullIfNotPresent,
		Ports:           BuildContainerPorts(in.ServiceConfig),
		EnvFrom: []corev1.EnvFromSource{
			{ConfigMapRef: &corev1.ConfigMapEnvSource{LocalObjectReference: corev1.LocalObjectReference{Name: in.ApplicationName}, Optional: domain.Ptr(true)}},
			{SecretRef: &corev1.SecretEnvSource{LocalObjectReference: corev1.LocalObjectReference{Name: in.ApplicationName}, Optional: domain.Ptr(true)}},
		},
		VolumeMounts: in.VolumeMounts,
	}
	if requirements := BuildResourceRequirements(in.RuntimeSpec); requirements != nil {
		container.Resources = *requirements
	}
	if in.ServiceConfig != nil && in.ServiceConfig.Port != nil && *in.ServiceConfig.Port > 0 && in.HealthCheck != nil {
		appPort := *in.ServiceConfig.Port
		container.LivenessProbe = BuildProbe(in.HealthCheck.LivenessOrDefault(), appPort)
		container.ReadinessProbe = BuildProbe(in.HealthCheck.ReadinessOrDefault(), appPort)
	}

	replicas := int32(0)
	if in.RuntimeSpec.Replicas != nil {
		replicas = int32(*in.RuntimeSpec.Replicas)
	}
	podSpec := corev1.PodSpec{
		EnableServiceLinks: domain.Ptr(false),
		Containers:         []corev1.Container{container},
		ImagePullSecrets:   []corev1.LocalObjectReference{{Name: ImagePullSecretName}},
		Affinity:           RequireNodesAffinity(in.ExpertConfig.NodeNames),
	}
	if len(in.Volumes) > 0 {
		podSpec.Volumes = in.Volumes
	}
	if !blankPtr(in.ExpertConfig.ServiceAccountName) {
		podSpec.ServiceAccountName = *in.ExpertConfig.ServiceAccountName
	}
	podSpec.PriorityClassName = domain.PriorityFromValue(in.ExpertConfig.Priority).PriorityClassName()

	return &appsv1.StatefulSet{
		TypeMeta: metav1.TypeMeta{APIVersion: "apps/v1", Kind: "StatefulSet"},
		ObjectMeta: metav1.ObjectMeta{
			Name:        in.ApplicationName,
			Labels:      labels,
			Annotations: annotations,
		},
		Spec: appsv1.StatefulSetSpec{
			Replicas:    &replicas,
			ServiceName: in.ApplicationName,
			Selector:    &metav1.LabelSelector{MatchLabels: labels},
			Template: corev1.PodTemplateSpec{
				ObjectMeta: metav1.ObjectMeta{Labels: labels},
				Spec:       podSpec,
			},
		},
	}
}

// BuildResourceRequirements mirrors StatefulSetProcessor's resources block:
// nil when all four strings are blank, cpu verbatim, memory suffixed with "Mi".
func BuildResourceRequirements(spec domain.RuntimeEnvironmentConfig) *corev1.ResourceRequirements {
	if blankPtr(spec.CPURequest) && blankPtr(spec.CPULimit) && blankPtr(spec.MemoryRequest) && blankPtr(spec.MemoryLimit) {
		return nil
	}
	requirements := &corev1.ResourceRequirements{Requests: corev1.ResourceList{}, Limits: corev1.ResourceList{}}
	if !blankPtr(spec.CPURequest) {
		requirements.Requests[corev1.ResourceCPU] = resource.MustParse(strings.TrimSpace(*spec.CPURequest))
	}
	if !blankPtr(spec.CPULimit) {
		requirements.Limits[corev1.ResourceCPU] = resource.MustParse(strings.TrimSpace(*spec.CPULimit))
	}
	if !blankPtr(spec.MemoryRequest) {
		requirements.Requests[corev1.ResourceMemory] = resource.MustParse(strings.TrimSpace(*spec.MemoryRequest) + "Mi")
	}
	if !blankPtr(spec.MemoryLimit) {
		requirements.Limits[corev1.ResourceMemory] = resource.MustParse(strings.TrimSpace(*spec.MemoryLimit) + "Mi")
	}
	if len(requirements.Requests) == 0 {
		requirements.Requests = nil
	}
	if len(requirements.Limits) == 0 {
		requirements.Limits = nil
	}
	return requirements
}

// BuildContainerPorts mirrors the port naming rules: "http" for the app port,
// "tcp-<n>" for internal ports other than the app port and 80.
func BuildContainerPorts(serviceConfig *domain.ApplicationServiceConfig) []corev1.ContainerPort {
	if serviceConfig == nil {
		return nil
	}
	var ports []corev1.ContainerPort
	appPort := 0
	if serviceConfig.Port != nil && *serviceConfig.Port > 0 {
		appPort = *serviceConfig.Port
		ports = append(ports, corev1.ContainerPort{Name: "http", ContainerPort: int32(appPort)})
	}
	for _, internalPort := range serviceConfig.DistinctInternalPorts() {
		if internalPort == appPort || internalPort == ServicePort {
			continue
		}
		ports = append(ports, corev1.ContainerPort{Name: fmt.Sprintf("tcp-%d", internalPort), ContainerPort: int32(internalPort)})
	}
	return ports
}

// BuildProbe returns the HTTP probe for an enabled probe config, nil otherwise.
func BuildProbe(probe domain.Probe, appPort int) *corev1.Probe {
	if !probe.ProbeEnabled() {
		return nil
	}
	return &corev1.Probe{
		ProbeHandler: corev1.ProbeHandler{
			HTTPGet: &corev1.HTTPGetAction{Path: probe.NormalizedPath(), Port: intstr.FromInt(appPort)},
		},
		InitialDelaySeconds: int32(probe.EffectiveInitialDelay()),
		PeriodSeconds:       int32(probe.EffectivePeriod()),
		TimeoutSeconds:      int32(probe.EffectiveTimeout()),
		FailureThreshold:    int32(probe.EffectiveFailureThreshold()),
	}
}

// RequireNodesAffinity mirrors KubernetesNodeAffinities.requireNodes.
func RequireNodesAffinity(nodeNames []string) *corev1.Affinity {
	if len(nodeNames) == 0 {
		return nil
	}
	return &corev1.Affinity{
		NodeAffinity: &corev1.NodeAffinity{
			RequiredDuringSchedulingIgnoredDuringExecution: &corev1.NodeSelector{
				NodeSelectorTerms: []corev1.NodeSelectorTerm{{
					MatchExpressions: []corev1.NodeSelectorRequirement{{
						Key:      "kubernetes.io/hostname",
						Operator: corev1.NodeSelectorOpIn,
						Values:   nodeNames,
					}},
				}},
			},
		},
	}
}

// configFileProjection reads the <app>.files ConfigMap and Secret and turns
// every mounted key into a volume + mount pair.
func configFileProjection(ctx context.Context, clientset kubernetes.Interface, namespace, applicationName string) ([]corev1.Volume, []corev1.VolumeMount, error) {
	filesName := applicationName + FilesResourceSuffix
	var volumes []corev1.Volume
	var mounts []corev1.VolumeMount

	configMap, err := clientset.CoreV1().ConfigMaps(namespace).Get(ctx, filesName, metav1.GetOptions{})
	if err != nil && !IsNotFound(err) {
		return nil, nil, err
	}
	if err == nil && len(configMap.Data) > 0 {
		mountPaths := readMounts(configMap.Annotations)
		for _, key := range sortedKeys(configMap.Data) {
			mountPath := mountPaths[key]
			if isBlank(mountPath) {
				continue
			}
			volumeName := fmt.Sprintf("config-%s-%d", domain.ToResourceName(key), len(volumes))
			fileName := fileNameOf(mountPath)
			volumes = append(volumes, corev1.Volume{
				Name: volumeName,
				VolumeSource: corev1.VolumeSource{ConfigMap: &corev1.ConfigMapVolumeSource{
					LocalObjectReference: corev1.LocalObjectReference{Name: filesName},
					Items:                []corev1.KeyToPath{{Key: key, Path: fileName}},
				}},
			})
			mounts = append(mounts, corev1.VolumeMount{Name: volumeName, MountPath: mountPath, SubPath: fileName, ReadOnly: true})
		}
	}

	secret, err := clientset.CoreV1().Secrets(namespace).Get(ctx, filesName, metav1.GetOptions{})
	if err != nil && !IsNotFound(err) {
		return nil, nil, err
	}
	if err == nil && len(secret.Data) > 0 {
		mountPaths := readMounts(secret.Annotations)
		for _, key := range sortedKeys(secret.Data) {
			mountPath := mountPaths[key]
			if isBlank(mountPath) {
				continue
			}
			volumeName := fmt.Sprintf("secret-%s-%d", domain.ToResourceName(key), len(volumes))
			fileName := fileNameOf(mountPath)
			volumes = append(volumes, corev1.Volume{
				Name: volumeName,
				VolumeSource: corev1.VolumeSource{Secret: &corev1.SecretVolumeSource{
					SecretName: filesName,
					Items:      []corev1.KeyToPath{{Key: key, Path: fileName}},
				}},
			})
			mounts = append(mounts, corev1.VolumeMount{Name: volumeName, MountPath: mountPath, SubPath: fileName, ReadOnly: true})
		}
	}
	return volumes, mounts, nil
}

// fileNameOf returns the last path segment of a mount path.
func fileNameOf(mountPath string) string {
	trimmed := strings.TrimRight(strings.TrimSpace(mountPath), "/")
	if index := strings.LastIndex(trimmed, "/"); index >= 0 {
		return trimmed[index+1:]
	}
	return trimmed
}

// DeleteRolloutBlockingPods mirrors RolloutUnsticker.deleteRolloutBlockingPods:
// delete every application pod that is neither terminating nor running-and-ready.
// Best effort — failures are logged, never returned.
func DeleteRolloutBlockingPods(ctx context.Context, client *Client, namespace string, podLabels map[string]string) {
	selector := metav1.FormatLabelSelector(&metav1.LabelSelector{MatchLabels: podLabels})
	pods, err := client.Clientset.CoreV1().Pods(namespace).List(ctx, metav1.ListOptions{LabelSelector: selector})
	if err != nil {
		slog.Warn("Failed to list pods while checking for rollout-blocking pods", "namespace", namespace, "error", err.Error())
		return
	}
	for index := range pods.Items {
		pod := &pods.Items[index]
		if podIsTerminating(pod) || podIsRunningAndReady(pod) {
			continue
		}
		slog.Info("Deleting not-ready pod so the StatefulSet controller can replace it with the updated template", "namespace", namespace, "pod", pod.Name)
		if err := client.Clientset.CoreV1().Pods(namespace).Delete(ctx, pod.Name, metav1.DeleteOptions{}); err != nil {
			slog.Warn("Failed to delete not-ready pod; the rollout may stay blocked until it is deleted manually", "namespace", namespace, "pod", pod.Name, "error", err.Error())
		}
	}
}
