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
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/apimachinery/pkg/util/intstr"
	"k8s.io/client-go/dynamic"

	"github.com/wellch4n/oops/server/internal/k8s"
	"github.com/wellch4n/oops/server/internal/store"
)

const (
	fieldManager                = "oops"
	rolloutStartedAtAnnotation  = "oops.rollout.started-at"
	pipelineIDAnnotation        = "oops.pipeline.id"
	redirectMiddlewareName      = "oops-redirect-https"
	basicAuthLabelKey           = "oops.resource"
	basicAuthLabelValue         = "basic-auth"
	deployServicePort           = 80
	applicationTypeLabel        = "oops.type"
	applicationNameLabel        = "oops.app.name"
	applicationTypeLabelValue   = "APPLICATION"
	dockerhubSecretName         = "dockerhub"
	defaultImagePullSecret      = "dockerhub"
	statefulSetKind             = "StatefulSet"
	statefulSetAPIVersion       = "apps/v1"
	middlewareResourceName      = "middlewares"
	traefikGroup                = "traefik.io"
	traefikVersion              = "v1alpha1"
	ingressRouteCRDResourceName = "ingressroutes.traefik.io"
)

var middlewareGVR = schema.GroupVersionResource{Group: traefikGroup, Version: traefikVersion, Resource: middlewareResourceName}

// deployInput is the Go DeployContext.
type deployInput struct {
	Pipeline      *store.PipelineView
	Namespace     string
	Application   string
	Environment   *store.EnvironmentFull
	RuntimeSpec   *store.RuntimeEnvironmentConfig
	HealthCheck   *store.HealthCheck
	ServiceConfig *store.ServiceConfigView
	ExpertConfig  *store.ExpertEnvironmentConfig
	CertResolver  string
	Domains       []store.DomainFull
}

func applicationLabels(applicationName string) map[string]string {
	return map[string]string{
		applicationTypeLabel: applicationTypeLabelValue,
		applicationNameLabel: applicationName,
	}
}

func serverSideApply(ctx context.Context, cluster *k8s.Cluster, object any, gvr schema.GroupVersionResource, namespace, name string) error {
	payload, err := json.Marshal(object)
	if err != nil {
		return err
	}
	dynamicClient, err := dynamic.NewForConfig(cluster.Config)
	if err != nil {
		return err
	}
	_, err = dynamicClient.Resource(gvr).Namespace(namespace).Patch(ctx, name, types.ApplyPatchType, payload,
		metav1.PatchOptions{FieldManager: fieldManager, Force: boolPointer(true)})
	return err
}

func boolPointer(value bool) *bool { return &value }

// Deploy runs the processor chain, mirroring ArtifactDeployTask.
func Deploy(ctx context.Context, cluster *k8s.Cluster, input *deployInput) error {
	if err := processNamespace(ctx, cluster, input); err != nil {
		return err
	}
	if err := processImagePullSecret(ctx, cluster, input); err != nil {
		return err
	}
	if err := processPriorityClass(ctx, cluster, input); err != nil {
		return err
	}
	ownerReference, err := processStatefulSet(ctx, cluster, input)
	if err != nil {
		return err
	}
	if err := processService(ctx, cluster, input, ownerReference); err != nil {
		return err
	}
	return processIngressRoutes(ctx, cluster, input, ownerReference)
}

func processNamespace(ctx context.Context, cluster *k8s.Cluster, input *deployInput) error {
	namespace := map[string]any{
		"apiVersion": "v1", "kind": "Namespace",
		"metadata": map[string]any{"name": input.Namespace},
	}
	return serverSideApply(ctx, cluster, namespace,
		schema.GroupVersionResource{Version: "v1", Resource: "namespaces"}, "", input.Namespace)
}

func processImagePullSecret(ctx context.Context, cluster *k8s.Cluster, input *deployInput) error {
	workNamespace := ""
	if input.Environment.WorkNamespace != nil {
		workNamespace = *input.Environment.WorkNamespace
	}
	if workNamespace == "" {
		return nil
	}
	source, err := cluster.Clientset.CoreV1().Secrets(workNamespace).Get(ctx, dockerhubSecretName, metav1.GetOptions{})
	if apierrors.IsNotFound(err) {
		return nil
	}
	if err != nil {
		return err
	}
	target := &corev1.Secret{
		TypeMeta:   metav1.TypeMeta{APIVersion: "v1", Kind: "Secret"},
		ObjectMeta: metav1.ObjectMeta{Name: dockerhubSecretName, Namespace: input.Namespace},
		Type:       source.Type,
		Data:       source.Data,
	}
	return serverSideApply(ctx, cluster, target,
		schema.GroupVersionResource{Version: "v1", Resource: "secrets"}, input.Namespace, dockerhubSecretName)
}

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

func processService(ctx context.Context, cluster *k8s.Cluster, input *deployInput, owner *metav1.OwnerReference) error {
	if input.ServiceConfig == nil || input.ServiceConfig.Port == nil {
		return nil
	}
	applicationPort := *input.ServiceConfig.Port
	labels := applicationLabels(input.Application)
	ports := []corev1.ServicePort{{
		Name: "web", Protocol: corev1.ProtocolTCP,
		Port: deployServicePort, TargetPort: intstr.FromInt(applicationPort),
	}}
	for _, internalPort := range distinctInternalPorts(input.ServiceConfig) {
		if internalPort == deployServicePort {
			continue
		}
		ports = append(ports, corev1.ServicePort{
			Name: fmt.Sprintf("tcp-%d", internalPort), Protocol: corev1.ProtocolTCP,
			Port: int32(internalPort), TargetPort: intstr.FromInt(internalPort),
		})
	}
	service := &corev1.Service{
		TypeMeta: metav1.TypeMeta{APIVersion: "v1", Kind: "Service"},
		ObjectMeta: metav1.ObjectMeta{
			Name: input.Application, Namespace: input.Namespace,
			Labels:          labels,
			OwnerReferences: []metav1.OwnerReference{*owner},
		},
		Spec: corev1.ServiceSpec{
			Type:     corev1.ServiceTypeClusterIP,
			Selector: labels,
			Ports:    ports,
		},
	}
	return serverSideApply(ctx, cluster, service,
		schema.GroupVersionResource{Version: "v1", Resource: "services"}, input.Namespace, input.Application)
}

func ingressRouteName(applicationName, host, suffix string) string {
	return applicationName + "-" + suffix + "-" + strings.ReplaceAll(host, ".", "-")
}

func basicAuthResourceName(applicationName, host string) string {
	return applicationName + "-basic-auth-" + strings.ReplaceAll(host, ".", "-")
}

func processIngressRoutes(ctx context.Context, cluster *k8s.Cluster, input *deployInput, owner *metav1.OwnerReference) error {
	if input.ServiceConfig == nil {
		return nil
	}
	hostConfigs := []store.ServiceEnvironmentConfigStored{}
	for _, config := range input.ServiceConfig.StoredEnvironmentConfigs {
		if config.EnvironmentName != nil && *config.EnvironmentName == input.Environment.Name &&
			config.Host != nil && *config.Host != "" {
			hostConfigs = append(hostConfigs, config)
		}
	}
	if len(hostConfigs) == 0 {
		return nil
	}

	// Skip gracefully when the Traefik CRD is absent, like the Java processor.
	if _, err := cluster.Clientset.Discovery().ServerResourcesForGroupVersion(traefikGroup + "/" + traefikVersion); err != nil {
		return nil
	}
	dynamicClient, err := dynamic.NewForConfig(cluster.Config)
	if err != nil {
		return err
	}

	labels := applicationLabels(input.Application)
	ownerMap := []any{map[string]any{
		"apiVersion": owner.APIVersion, "kind": owner.Kind, "name": owner.Name,
		"uid": string(owner.UID), "controller": true, "blockOwnerDeletion": true,
	}}

	applyRoute := func(name, host string, entryPoints []string, tls map[string]any, middlewares []string) error {
		route := map[string]any{
			"match": "Host(`" + host + "`)",
			"kind":  "Rule",
			"services": []any{map[string]any{
				"name": input.Application, "port": deployServicePort,
			}},
		}
		if len(middlewares) > 0 {
			middlewareRefs := []any{}
			for _, middlewareName := range middlewares {
				middlewareRefs = append(middlewareRefs, map[string]any{"name": middlewareName})
			}
			route["middlewares"] = middlewareRefs
		}
		spec := map[string]any{
			"routes":      []any{route},
			"entryPoints": entryPoints,
		}
		if tls != nil {
			spec["tls"] = tls
		}
		object := map[string]any{
			"apiVersion": traefikGroup + "/" + traefikVersion,
			"kind":       "IngressRoute",
			"metadata": map[string]any{
				"name": name, "namespace": input.Namespace,
				"labels": labels, "ownerReferences": ownerMap,
			},
			"spec": spec,
		}
		payload, err := json.Marshal(object)
		if err != nil {
			return err
		}
		_, err = dynamicClient.Resource(k8s.IngressRouteGVR).Namespace(input.Namespace).
			Patch(ctx, name, types.ApplyPatchType, payload,
				metav1.PatchOptions{FieldManager: fieldManager, Force: boolPointer(true)})
		return err
	}

	appliedNames := map[string]struct{}{}
	appliedBasicAuth := map[string]struct{}{}
	for _, config := range hostConfigs {
		host := *config.Host
		https := config.HTTPS != nil && *config.HTTPS

		serveMiddlewares := []string{}
		if config.BasicAuthEnabled != nil && *config.BasicAuthEnabled &&
			config.BasicAuthUsername != nil && *config.BasicAuthUsername != "" &&
			config.BasicAuthPasswordHash != nil && *config.BasicAuthPasswordHash != "" {
			basicAuthName := basicAuthResourceName(input.Application, host)
			if err := ensureBasicAuthMiddleware(ctx, cluster, dynamicClient, input, basicAuthName,
				*config.BasicAuthUsername, *config.BasicAuthPasswordHash, owner); err != nil {
				return err
			}
			appliedBasicAuth[basicAuthName] = struct{}{}
			serveMiddlewares = []string{basicAuthName}
		}

		if https {
			if err := ensureRedirectMiddleware(ctx, dynamicClient, input.Namespace); err != nil {
				return err
			}
			httpName := ingressRouteName(input.Application, host, "http")
			appliedNames[httpName] = struct{}{}
			if err := applyRoute(httpName, host, []string{"web"}, nil, []string{redirectMiddlewareName}); err != nil {
				return err
			}
			httpsName := ingressRouteName(input.Application, host, "https")
			appliedNames[httpsName] = struct{}{}
			tls, err := buildTLSForHost(ctx, cluster, input, host)
			if err != nil {
				return err
			}
			if err := applyRoute(httpsName, host, []string{"websecure"}, tls, serveMiddlewares); err != nil {
				return err
			}
		} else {
			httpName := ingressRouteName(input.Application, host, "http")
			appliedNames[httpName] = struct{}{}
			if err := applyRoute(httpName, host, []string{"web"}, nil, serveMiddlewares); err != nil {
				return err
			}
		}
	}

	// Delete IngressRoutes for hosts no longer configured.
	existing, err := dynamicClient.Resource(k8s.IngressRouteGVR).Namespace(input.Namespace).
		List(ctx, metav1.ListOptions{LabelSelector: applicationNameLabel + "=" + input.Application})
	if err == nil {
		for _, item := range existing.Items {
			if _, kept := appliedNames[item.GetName()]; !kept {
				_ = dynamicClient.Resource(k8s.IngressRouteGVR).Namespace(input.Namespace).
					Delete(ctx, item.GetName(), metav1.DeleteOptions{})
			}
		}
	}
	// Delete stale basic-auth middlewares + secrets.
	staleMiddlewares, err := dynamicClient.Resource(middlewareGVR).Namespace(input.Namespace).
		List(ctx, metav1.ListOptions{
			LabelSelector: applicationNameLabel + "=" + input.Application + "," + basicAuthLabelKey + "=" + basicAuthLabelValue,
		})
	if err == nil {
		for _, item := range staleMiddlewares.Items {
			if _, kept := appliedBasicAuth[item.GetName()]; !kept {
				_ = dynamicClient.Resource(middlewareGVR).Namespace(input.Namespace).
					Delete(ctx, item.GetName(), metav1.DeleteOptions{})
				_ = cluster.Clientset.CoreV1().Secrets(input.Namespace).
					Delete(ctx, item.GetName(), metav1.DeleteOptions{})
			}
		}
	}
	return nil
}

func ensureRedirectMiddleware(ctx context.Context, dynamicClient dynamic.Interface, namespace string) error {
	_, err := dynamicClient.Resource(middlewareGVR).Namespace(namespace).Get(ctx, redirectMiddlewareName, metav1.GetOptions{})
	if err == nil {
		return nil
	}
	if !apierrors.IsNotFound(err) {
		return err
	}
	middleware := &unstructured.Unstructured{Object: map[string]any{
		"apiVersion": traefikGroup + "/" + traefikVersion,
		"kind":       "Middleware",
		"metadata":   map[string]any{"name": redirectMiddlewareName, "namespace": namespace},
		"spec": map[string]any{
			"redirectScheme": map[string]any{"scheme": "https", "permanent": true},
		},
	}}
	_, err = dynamicClient.Resource(middlewareGVR).Namespace(namespace).Create(ctx, middleware, metav1.CreateOptions{})
	if apierrors.IsAlreadyExists(err) {
		return nil
	}
	return err
}

func ensureBasicAuthMiddleware(ctx context.Context, cluster *k8s.Cluster, dynamicClient dynamic.Interface,
	input *deployInput, resourceName, username, passwordHash string, owner *metav1.OwnerReference) error {

	labels := applicationLabels(input.Application)
	labels[basicAuthLabelKey] = basicAuthLabelValue

	secret := &corev1.Secret{
		TypeMeta: metav1.TypeMeta{APIVersion: "v1", Kind: "Secret"},
		ObjectMeta: metav1.ObjectMeta{
			Name: resourceName, Namespace: input.Namespace,
			Labels:          labels,
			OwnerReferences: []metav1.OwnerReference{*owner},
		},
		Type:       corev1.SecretTypeOpaque,
		StringData: map[string]string{"users": username + ":" + passwordHash},
	}
	if err := serverSideApply(ctx, cluster, secret,
		schema.GroupVersionResource{Version: "v1", Resource: "secrets"}, input.Namespace, resourceName); err != nil {
		return err
	}
	middleware := map[string]any{
		"apiVersion": traefikGroup + "/" + traefikVersion,
		"kind":       "Middleware",
		"metadata": map[string]any{
			"name": resourceName, "namespace": input.Namespace, "labels": labels,
			"ownerReferences": []any{map[string]any{
				"apiVersion": owner.APIVersion, "kind": owner.Kind, "name": owner.Name,
				"uid": string(owner.UID), "controller": true, "blockOwnerDeletion": true,
			}},
		},
		"spec": map[string]any{"basicAuth": map[string]any{"secret": resourceName}},
	}
	payload, err := json.Marshal(middleware)
	if err != nil {
		return err
	}
	_, err = dynamicClient.Resource(middlewareGVR).Namespace(input.Namespace).
		Patch(ctx, resourceName, types.ApplyPatchType, payload,
			metav1.PatchOptions{FieldManager: fieldManager, Force: boolPointer(true)})
	return err
}

// buildTLSForHost mirrors buildTlsForHost: longest-suffix domain match;
// UPLOADED domains sync a TLS secret, otherwise the cert resolver is used.
func buildTLSForHost(ctx context.Context, cluster *k8s.Cluster, input *deployInput, host string) (map[string]any, error) {
	var matched *store.DomainFull
	longest := -1
	for i := range input.Domains {
		domain := &input.Domains[i]
		if domain.Host == "" {
			continue
		}
		if (host == domain.Host || strings.HasSuffix(host, "."+domain.Host)) && len(domain.Host) > longest {
			matched = domain
			longest = len(domain.Host)
		}
	}
	if matched != nil && matched.CertMode != nil && *matched.CertMode == "UPLOADED" &&
		matched.CertPem != "" && matched.KeyPem != "" {
		secretName := "domain-" + strings.ReplaceAll(matched.Host, ".", "-")
		secret := &corev1.Secret{
			TypeMeta:   metav1.TypeMeta{APIVersion: "v1", Kind: "Secret"},
			ObjectMeta: metav1.ObjectMeta{Name: secretName, Namespace: input.Namespace},
			Type:       corev1.SecretTypeTLS,
			StringData: map[string]string{"tls.crt": matched.CertPem, "tls.key": matched.KeyPem},
		}
		if err := serverSideApply(ctx, cluster, secret,
			schema.GroupVersionResource{Version: "v1", Resource: "secrets"}, input.Namespace, secretName); err != nil {
			return nil, err
		}
		return map[string]any{"secretName": secretName}, nil
	}
	return map[string]any{"certResolver": input.CertResolver}, nil
}
