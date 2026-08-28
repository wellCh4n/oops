package k8s

import (
	"context"
	_ "embed"
	"encoding/json"
	"fmt"
	"sort"
	"strings"

	"github.com/wellch4n/oops/server/internal/domain"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/apimachinery/pkg/util/intstr"
	"k8s.io/client-go/dynamic"
)

//go:embed ide-default-config.json
var ideDefaultConfigRaw []byte

// Volume mounts shared with the pipeline build pods (WorkspaceVolume + SecretVolume).
var workspaceMount = corev1.VolumeMount{Name: "workspace", MountPath: "/workspace"}

func secretMounts() []corev1.VolumeMount {
	return []corev1.VolumeMount{
		{Name: "registry-secret", MountPath: "/var/buildah/.docker"},
		{Name: "git-secret", MountPath: "/root/.netrc", SubPath: ".netrc"},
		{Name: "git-secret", MountPath: "/root/.ssh/id_rsa", SubPath: "id_rsa"},
	}
}

// IDESettings mirrors IDEProperties.
type IDESettings struct {
	Domain       string
	HTTPS        bool
	Image        string
	Middlewares  []string
	CloneImage   string
	CertResolver string
}

// IDEConfig mirrors IDEConfigDto: {settings, env, extensions}.
type IDEConfig struct {
	Settings   string `json:"settings"`
	Env        string `json:"env"`
	Extensions string `json:"extensions"`
}

func ideFileDefaults() IDEConfig {
	var root struct {
		Settings   json.RawMessage `json:"settings"`
		Env        string          `json:"env"`
		Extensions string          `json:"extensions"`
	}
	if err := json.Unmarshal(ideDefaultConfigRaw, &root); err != nil {
		return IDEConfig{Settings: "{}"}
	}
	return IDEConfig{Settings: string(root.Settings), Env: root.Env, Extensions: root.Extensions}
}

// GetDefaultIDEConfig mirrors the gateway: the work namespace's ide-config
// ConfigMap overrides the file defaults and is auto-created on first read.
func GetDefaultIDEConfig(ctx context.Context, cluster *Cluster, workNamespace string) (IDEConfig, error) {
	fileDefaults := ideFileDefaults()
	if cluster == nil || workNamespace == "" {
		return fileDefaults, nil
	}
	configMaps := cluster.Clientset.CoreV1().ConfigMaps(workNamespace)
	configMap, err := configMaps.Get(ctx, "ide-config", metav1.GetOptions{})
	if err == nil && configMap.Data != nil {
		settings, hasSettings := configMap.Data["settings.json"]
		env, hasEnv := configMap.Data[".env"]
		extensions, hasExtensions := configMap.Data["extensions"]
		if hasSettings && hasEnv && hasExtensions {
			return IDEConfig{Settings: settings, Env: env, Extensions: extensions}, nil
		}
	}
	data := map[string]string{}
	if err == nil {
		for key, value := range configMap.Data {
			data[key] = value
		}
	}
	data["settings.json"] = fileDefaults.Settings
	data[".env"] = fileDefaults.Env
	data["extensions"] = fileDefaults.Extensions
	payload, marshalError := json.Marshal(map[string]any{
		"apiVersion": "v1", "kind": "ConfigMap",
		"metadata": map[string]any{"name": "ide-config", "namespace": workNamespace},
		"data":     data,
	})
	if marshalError == nil {
		_, _ = configMaps.Patch(ctx, "ide-config", types.ApplyPatchType, payload,
			metav1.PatchOptions{FieldManager: fieldManager, Force: boolTrue()})
	}
	return fileDefaults, nil
}

func boolTrue() *bool {
	value := true
	return &value
}

// IDEView mirrors IdeDto.
type IDEView struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Host      string `json:"host"`
	HTTPS     bool   `json:"https"`
	CreatedAt string `json:"createdAt"`
	Ready     bool   `json:"ready"`
}

// CreateIDERequest mirrors CreateIDECommand.
type CreateIDERequest struct {
	Name       string `json:"name"`
	Branch     string `json:"branch"`
	Settings   string `json:"settings"`
	Env        string `json:"env"`
	Extensions string `json:"extensions"`
}

// CreateIDE builds the code-server StatefulSet, Service and IngressRoute
// (IngressRoutes cascade via ownerReference on delete).
func CreateIDE(ctx context.Context, cluster *Cluster, workNamespace, namespace, applicationName, repository string,
	settings IDESettings, request *CreateIDERequest) (string, error) {

	ideID := domain.NewID()
	name := applicationName + "-ide-" + ideID
	labels := map[string]string{
		"oops.type":   "IDE",
		"oops.app":    applicationName,
		"oops.ide.id": ideID,
	}
	annotations := map[string]string{}
	if strings.TrimSpace(request.Name) != "" {
		annotations["oops.ide.name"] = strings.TrimSpace(request.Name)
	}

	if repository == "" {
		return "", fmt.Errorf("repository URL must not be empty")
	}
	cloneArguments := []string{"git", "clone", "--progress"}
	if strings.TrimSpace(request.Branch) != "" {
		cloneArguments = append(cloneArguments, "-b", strings.TrimSpace(request.Branch))
	}
	cloneArguments = append(cloneArguments, repository, "/workspace")

	clone := corev1.Container{
		Name:    "fetch",
		Image:   settings.CloneImage,
		Command: []string{"sh", "-c", strings.Join(cloneArguments, " ")},
		Env: []corev1.EnvVar{{
			Name:  "GIT_SSH_COMMAND",
			Value: "ssh -i /root/.ssh/id_rsa -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR",
		}},
		VolumeMounts: append([]corev1.VolumeMount{workspaceMount}, secretMounts()...),
	}

	ideSettings := strings.TrimSpace(request.Settings)
	if ideSettings == "" {
		defaults, _ := GetDefaultIDEConfig(ctx, cluster, workNamespace)
		ideSettings = defaults.Settings
	} else {
		ideSettings = strings.Join(strings.Fields(ideSettings), " ")
	}

	environmentVariables := []corev1.EnvVar{}
	for _, line := range strings.Split(request.Env, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") || !strings.Contains(line, "=") {
			continue
		}
		name, value, _ := strings.Cut(line, "=")
		environmentVariables = append(environmentVariables, corev1.EnvVar{
			Name: strings.TrimSpace(name), Value: strings.TrimSpace(value),
		})
	}
	environmentVariables = append(environmentVariables, corev1.EnvVar{
		Name:  "EXTENSIONS_GALLERY",
		Value: `{"serviceUrl":"https://marketplace.visualstudio.com/_apis/public/gallery","itemUrl":"https://marketplace.visualstudio.com/items"}`,
	})

	startupCommands := []string{
		"cp -r /workspace /home/coder/" + applicationName,
		"mkdir -p /home/coder/.local/share/code-server/User",
		"echo '" + ideSettings + "' > /home/coder/.local/share/code-server/User/settings.json",
	}
	for _, extension := range strings.Split(request.Extensions, "\n") {
		if extension = strings.TrimSpace(extension); extension != "" {
			startupCommands = append(startupCommands, "code-server --install-extension "+extension)
		}
	}
	startupCommands = append(startupCommands,
		"code-server --bind-addr 0.0.0.0:1114 --auth none --disable-workspace-trust"+
			" --proxy-domain '{{port}}-{{host}}' /home/coder/"+applicationName)

	replicas := int32(1)
	statefulSet := &appsv1.StatefulSet{
		TypeMeta: metav1.TypeMeta{APIVersion: "apps/v1", Kind: "StatefulSet"},
		ObjectMeta: metav1.ObjectMeta{
			Name: name, Namespace: workNamespace, Labels: labels, Annotations: annotations,
		},
		Spec: appsv1.StatefulSetSpec{
			ServiceName: name,
			Replicas:    &replicas,
			Selector:    &metav1.LabelSelector{MatchLabels: labels},
			Template: corev1.PodTemplateSpec{
				ObjectMeta: metav1.ObjectMeta{Labels: labels},
				Spec: corev1.PodSpec{
					InitContainers: []corev1.Container{clone},
					Containers: []corev1.Container{{
						Name:         applicationName,
						Image:        settings.Image,
						VolumeMounts: []corev1.VolumeMount{workspaceMount},
						Env:          environmentVariables,
						Ports:        []corev1.ContainerPort{{ContainerPort: 1114}},
						Command:      []string{"sh", "-c", strings.Join(startupCommands, " && ")},
						ReadinessProbe: &corev1.Probe{
							ProbeHandler: corev1.ProbeHandler{
								HTTPGet: &corev1.HTTPGetAction{Path: "/", Port: intstr.FromInt(1114)},
							},
							InitialDelaySeconds: 5,
							PeriodSeconds:       5,
							FailureThreshold:    60,
						},
					}},
					Volumes: []corev1.Volume{
						{Name: "workspace", VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{}}},
						{Name: "registry-secret", VolumeSource: corev1.VolumeSource{
							Secret: &corev1.SecretVolumeSource{
								SecretName: "dockerhub",
								Items:      []corev1.KeyToPath{{Key: ".dockerconfigjson", Path: "config.json"}},
							},
						}},
						{Name: "git-secret", VolumeSource: corev1.VolumeSource{
							Secret: &corev1.SecretVolumeSource{
								SecretName:  "git-credential",
								Optional:    boolTrue(),
								DefaultMode: int32Pointer(0600),
								Items: []corev1.KeyToPath{
									{Key: ".netrc", Path: ".netrc"},
									{Key: "id_rsa", Path: "id_rsa"},
								},
							},
						}},
					},
				},
			},
		},
	}
	created, err := cluster.Clientset.AppsV1().StatefulSets(workNamespace).Create(ctx, statefulSet, metav1.CreateOptions{})
	if err != nil {
		return "", err
	}
	controller, block := true, true
	owner := metav1.OwnerReference{
		APIVersion: "apps/v1", Kind: "StatefulSet", Name: name, UID: created.UID,
		Controller: &controller, BlockOwnerDeletion: &block,
	}

	service := &corev1.Service{
		ObjectMeta: metav1.ObjectMeta{
			Name: name, Namespace: workNamespace, Labels: labels,
			OwnerReferences: []metav1.OwnerReference{owner},
		},
		Spec: corev1.ServiceSpec{
			Selector: labels,
			Ports:    []corev1.ServicePort{{Port: 80, TargetPort: intstr.FromInt(1114)}},
		},
	}
	if _, err := cluster.Clientset.CoreV1().Services(workNamespace).Create(ctx, service, metav1.CreateOptions{}); err != nil {
		_ = cluster.Clientset.AppsV1().StatefulSets(workNamespace).Delete(ctx, name, metav1.DeleteOptions{})
		return "", fmt.Errorf("IDE creation failed at Service, rolled back: %w", err)
	}

	if err := createIDEIngressRoute(ctx, cluster, workNamespace, name, settings, owner); err != nil {
		_ = cluster.Clientset.AppsV1().StatefulSets(workNamespace).Delete(ctx, name, metav1.DeleteOptions{})
		return "", fmt.Errorf("IDE creation failed at IngressRoute, rolled back: %w", err)
	}
	return ideID, nil
}

func int32Pointer(value int32) *int32 { return &value }

func createIDEIngressRoute(ctx context.Context, cluster *Cluster, workNamespace, name string, settings IDESettings, owner metav1.OwnerReference) error {
	// Skip gracefully when the Traefik CRD is absent, like the Java gateway.
	if _, err := cluster.Clientset.Discovery().ServerResourcesForGroupVersion("traefik.io/v1alpha1"); err != nil {
		return nil
	}
	dynamicClient, err := dynamic.NewForConfig(cluster.Config)
	if err != nil {
		return err
	}
	host := name + "." + settings.Domain
	matchRule := "Host(`" + host + "`) || HostRegexp(`^[0-9]+-" + strings.ReplaceAll(host, ".", `\.`) + "$`)"
	middlewares := []any{}
	for _, middlewareName := range settings.Middlewares {
		middlewares = append(middlewares, map[string]any{"name": middlewareName})
	}
	entryPoint := "web"
	if settings.HTTPS {
		entryPoint = "websecure"
	}
	route := map[string]any{
		"match":  matchRule,
		"syntax": "v3",
		"kind":   "Rule",
		"services": []any{map[string]any{
			"name": name, "port": 80,
		}},
	}
	if len(middlewares) > 0 {
		route["middlewares"] = middlewares
	}
	spec := map[string]any{
		"entryPoints": []any{entryPoint},
		"routes":      []any{route},
	}
	if settings.HTTPS {
		spec["tls"] = map[string]any{"certResolver": settings.CertResolver}
	}
	object := map[string]any{
		"apiVersion": "traefik.io/v1alpha1",
		"kind":       "IngressRoute",
		"metadata": map[string]any{
			"name": name, "namespace": workNamespace,
			"ownerReferences": []any{map[string]any{
				"apiVersion": owner.APIVersion, "kind": owner.Kind, "name": owner.Name,
				"uid": string(owner.UID), "controller": true, "blockOwnerDeletion": true,
			}},
		},
		"spec": spec,
	}
	payload, err := json.Marshal(object)
	if err != nil {
		return err
	}
	_, err = dynamicClient.Resource(IngressRouteGVR).Namespace(workNamespace).
		Patch(ctx, name, types.ApplyPatchType, payload,
			metav1.PatchOptions{FieldManager: fieldManager, Force: boolTrue()})
	return err
}

func DeleteIDE(ctx context.Context, cluster *Cluster, workNamespace, name string) error {
	err := cluster.Clientset.AppsV1().StatefulSets(workNamespace).Delete(ctx, name, metav1.DeleteOptions{})
	if apierrors.IsNotFound(err) {
		return nil
	}
	return err
}

func ListIDEs(ctx context.Context, cluster *Cluster, workNamespace, applicationName string, settings IDESettings) ([]IDEView, error) {
	statefulSets, err := cluster.Clientset.AppsV1().StatefulSets(workNamespace).List(ctx, metav1.ListOptions{
		LabelSelector: "oops.type=IDE,oops.app=" + applicationName,
	})
	if err != nil {
		return nil, err
	}
	views := []IDEView{}
	for i := range statefulSets.Items {
		statefulSet := &statefulSets.Items[i]
		id := statefulSet.Name
		name := statefulSet.Annotations["oops.ide.name"]
		if name == "" {
			name = id
		}
		views = append(views, IDEView{
			ID:        id,
			Name:      name,
			Host:      id + "." + settings.Domain,
			HTTPS:     settings.HTTPS,
			CreatedAt: statefulSet.CreationTimestamp.UTC().Format(time.RFC3339),
			Ready:     statefulSet.Status.ReadyReplicas > 0,
		})
	}
	sort.SliceStable(views, func(i, j int) bool { return views[i].CreatedAt > views[j].CreatedAt })
	return views, nil
}
