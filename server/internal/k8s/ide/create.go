package ide

import (
	"context"
	"log/slog"
	"regexp"
	"strings"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/k8s"
	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/apimachinery/pkg/util/intstr"
	"k8s.io/client-go/dynamic"
)

const (
	codeServerPort   = 1114
	fetchContainer   = "fetch"
	gitSSHCommand    = "ssh -i /root/.ssh/id_rsa -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR"
	extensionGallery = `{"serviceUrl":"https://marketplace.visualstudio.com/_apis/public/gallery","itemUrl":"https://marketplace.visualstudio.com/items"}`

	ingressRouteCRDName = "ingressroutes.traefik.io"
)

var (
	ingressRouteGVR = schema.GroupVersionResource{Group: "traefik.io", Version: "v1alpha1", Resource: "ingressroutes"}
	crdGVR          = schema.GroupVersionResource{Group: "apiextensions.k8s.io", Version: "v1", Resource: "customresourcedefinitions"}
	whitespaceRun   = regexp.MustCompile(`\s+`)
)

// creationError carries the exact RuntimeException message the Java gateway
// threw after rolling back, while keeping the cause for logging.
type creationError struct {
	message string
	cause   error
}

func (e *creationError) Error() string { return e.message }
func (e *creationError) Unwrap() error { return e.cause }

// Create provisions an IDE for the application and returns its id. repository
// is the application's build-config repository (nil when unset).
func (g *Gateway) Create(ctx context.Context, env *domain.Environment, namespace, app string, repository *string, req CreateRequest) (string, error) {
	ideID := domain.NewID()
	name := app + "-ide-" + ideID
	workNamespace := domain.Deref(env.WorkNamespace)

	settings := req.Settings
	if isBlank(settings) {
		defaults, err := g.DefaultConfig(ctx, env)
		if err != nil {
			return "", err
		}
		settings = defaults.Settings
	} else {
		settings = collapseWhitespace(settings)
	}

	fetch, err := buildFetchContainer(g.opts.CloneImage, app, domain.Deref(repository), req.Branch)
	if err != nil {
		return "", err
	}
	statefulSet := buildStatefulSet(name, app, ideID, req.Name, g.opts.Image, fetch, settings, parseEnv(req.Env), buildInstallCommands(req.Extensions))

	client, err := g.pool.Get(env.KubernetesApiServer)
	if err != nil {
		return "", k8s.TranslateError(err)
	}
	statefulSets := client.Clientset.AppsV1().StatefulSets(workNamespace)

	var created *appsv1.StatefulSet
	if err := serverSideApply(ctx, statefulSet, func(payload []byte) error {
		applied, applyErr := statefulSets.Patch(ctx, name, types.ApplyPatchType, payload, applyOptions())
		created = applied
		return applyErr
	}); err != nil {
		return "", k8s.TranslateError(err)
	}

	ownerRef := metav1.OwnerReference{
		APIVersion:         "apps/v1",
		Kind:               "StatefulSet",
		Name:               name,
		UID:                created.UID,
		Controller:         domain.Ptr(true),
		BlockOwnerDeletion: domain.Ptr(true),
	}

	service := buildService(name, statefulSet.Labels, ownerRef)
	if err := serverSideApply(ctx, service, func(payload []byte) error {
		_, applyErr := client.Clientset.CoreV1().Services(workNamespace).Patch(ctx, name, types.ApplyPatchType, payload, applyOptions())
		return applyErr
	}); err != nil {
		slog.Error("Failed to create IDE Service, rolling back StatefulSet", "name", name, "error", err)
		_ = statefulSets.Delete(ctx, name, metav1.DeleteOptions{})
		return "", &creationError{message: "IDE creation failed at Service, rolled back", cause: err}
	}

	if err := g.createIngressRoute(ctx, client.Dynamic, workNamespace, name, ownerRef); err != nil {
		slog.Error("Failed to create IDE IngressRoute, rolling back StatefulSet", "name", name, "error", err)
		_ = statefulSets.Delete(ctx, name, metav1.DeleteOptions{})
		return "", &creationError{message: "IDE creation failed at IngressRoute, rolled back", cause: err}
	}

	return ideID, nil
}

// collapseWhitespace mirrors settings.replaceAll("\\s+", " ").trim().
func collapseWhitespace(value string) string {
	return strings.TrimSpace(whitespaceRun.ReplaceAllString(value, " "))
}

// parseEnv turns dotenv-style text into env vars: blank lines, "#" comments
// and lines without "=" are skipped; name and value are trimmed. The
// EXTENSIONS_GALLERY entry is always appended last.
func parseEnv(text string) []corev1.EnvVar {
	var envVars []corev1.EnvVar
	for _, rawLine := range strings.Split(text, "\n") {
		line := strings.TrimSpace(strings.TrimSuffix(rawLine, "\r"))
		if line == "" || strings.HasPrefix(line, "#") || !strings.Contains(line, "=") {
			continue
		}
		name, value, _ := strings.Cut(line, "=")
		envVars = append(envVars, corev1.EnvVar{Name: strings.TrimSpace(name), Value: strings.TrimSpace(value)})
	}
	return append(envVars, corev1.EnvVar{Name: "EXTENSIONS_GALLERY", Value: extensionGallery})
}

// buildInstallCommands maps each non-blank extension line to an install command.
func buildInstallCommands(extensions string) []string {
	var commands []string
	for _, rawLine := range strings.Split(extensions, "\n") {
		extension := strings.TrimSpace(strings.TrimSuffix(rawLine, "\r"))
		if extension == "" {
			continue
		}
		commands = append(commands, "code-server --install-extension "+extension)
	}
	return commands
}

// buildStartupCommand assembles the code-server container's `sh -c` script.
func buildStartupCommand(app, settings string, installCommands []string) string {
	commands := []string{
		"cp -r /workspace /home/coder/" + app,
		"mkdir -p /home/coder/.local/share/code-server/User",
		"echo '" + settings + "' > /home/coder/.local/share/code-server/User/settings.json",
	}
	commands = append(commands, installCommands...)
	commands = append(commands, "code-server --bind-addr 0.0.0.0:1114 --auth none --disable-workspace-trust --proxy-domain '{{port}}-{{host}}' /home/coder/"+app)
	return strings.Join(commands, " && ")
}

// buildFetchContainer reproduces the pipeline's `fetch` init container for a
// full (non-shallow) clone; a blank repository is the same plain error the
// Java GitCloneStrategy raised.
func buildFetchContainer(cloneImage, app, repository, branch string) (corev1.Container, error) {
	if isBlank(repository) {
		return corev1.Container{}, &creationError{message: "Repository URL must not be empty for application: " + app}
	}
	arguments := []string{"git", "clone", "--progress"}
	if !isBlank(branch) {
		arguments = append(arguments, "-b", branch)
	}
	arguments = append(arguments, repository, "/workspace")

	mounts := []corev1.VolumeMount{workspaceMount()}
	mounts = append(mounts, secretMounts()...)
	return corev1.Container{
		Name:         fetchContainer,
		Image:        cloneImage,
		Command:      []string{"sh", "-c", strings.Join(arguments, " ")},
		Env:          []corev1.EnvVar{{Name: "GIT_SSH_COMMAND", Value: gitSSHCommand}},
		VolumeMounts: mounts,
	}, nil
}

func workspaceVolume() corev1.Volume {
	return corev1.Volume{Name: "workspace", VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{}}}
}

func workspaceMount() corev1.VolumeMount {
	return corev1.VolumeMount{Name: "workspace", MountPath: "/workspace"}
}

func secretVolumes() []corev1.Volume {
	return []corev1.Volume{
		{Name: "registry-secret", VolumeSource: corev1.VolumeSource{Secret: &corev1.SecretVolumeSource{
			SecretName: "dockerhub",
			Optional:   domain.Ptr(true),
			Items:      []corev1.KeyToPath{{Key: ".dockerconfigjson", Path: "config.json"}},
		}}},
		{Name: "git-secret", VolumeSource: corev1.VolumeSource{Secret: &corev1.SecretVolumeSource{
			SecretName:  "git-credential",
			Optional:    domain.Ptr(true),
			DefaultMode: domain.Ptr(int32(0600)),
			Items: []corev1.KeyToPath{
				{Key: ".netrc", Path: ".netrc"},
				{Key: "id_rsa", Path: "id_rsa"},
			},
		}}},
	}
}

func secretMounts() []corev1.VolumeMount {
	return []corev1.VolumeMount{
		{Name: "registry-secret", MountPath: "/var/buildah/.docker"},
		{Name: "git-secret", MountPath: "/root/.netrc", SubPath: ".netrc"},
		{Name: "git-secret", MountPath: "/root/.ssh/id_rsa", SubPath: "id_rsa"},
	}
}

func buildStatefulSet(name, app, ideID, displayName, image string, fetch corev1.Container, settings string, envVars []corev1.EnvVar, installCommands []string) *appsv1.StatefulSet {
	labels := map[string]string{LabelType: TypeValue, LabelApp: app, LabelIDEID: ideID}
	var annotations map[string]string
	if !isBlank(displayName) {
		annotations = map[string]string{AnnotationName: displayName}
	}

	volumes := []corev1.Volume{workspaceVolume()}
	volumes = append(volumes, secretVolumes()...)

	return &appsv1.StatefulSet{
		TypeMeta:   metav1.TypeMeta{APIVersion: "apps/v1", Kind: "StatefulSet"},
		ObjectMeta: metav1.ObjectMeta{Name: name, Labels: labels, Annotations: annotations},
		Spec: appsv1.StatefulSetSpec{
			ServiceName: name,
			Replicas:    domain.Ptr(int32(1)),
			Selector:    &metav1.LabelSelector{MatchLabels: copyMap(labels)},
			Template: corev1.PodTemplateSpec{
				ObjectMeta: metav1.ObjectMeta{Labels: copyMap(labels)},
				Spec: corev1.PodSpec{
					InitContainers: []corev1.Container{fetch},
					Containers: []corev1.Container{{
						Name:         app,
						Image:        image,
						VolumeMounts: []corev1.VolumeMount{workspaceMount()},
						Env:          envVars,
						Ports:        []corev1.ContainerPort{{ContainerPort: codeServerPort}},
						Command:      []string{"sh", "-c", buildStartupCommand(app, settings, installCommands)},
						ReadinessProbe: &corev1.Probe{
							ProbeHandler: corev1.ProbeHandler{HTTPGet: &corev1.HTTPGetAction{
								Path: "/",
								Port: intstr.FromInt32(codeServerPort),
							}},
							InitialDelaySeconds: 5,
							PeriodSeconds:       5,
							FailureThreshold:    60,
						},
					}},
					Volumes: volumes,
				},
			},
		},
	}
}

func buildService(name string, labels map[string]string, ownerRef metav1.OwnerReference) *corev1.Service {
	return &corev1.Service{
		TypeMeta:   metav1.TypeMeta{APIVersion: "v1", Kind: "Service"},
		ObjectMeta: metav1.ObjectMeta{Name: name, Labels: copyMap(labels), OwnerReferences: []metav1.OwnerReference{ownerRef}},
		Spec: corev1.ServiceSpec{
			Ports:    []corev1.ServicePort{{Port: 80, TargetPort: intstr.FromInt32(codeServerPort)}},
			Selector: copyMap(labels),
		},
	}
}

// ingressMatchRule builds the Traefik v3 rule: the plain host or the
// "<port>-<host>" proxy-domain form, with dots escaped in the regexp.
func ingressMatchRule(host string) string {
	return "Host(`" + host + "`) || HostRegexp(`^[0-9]+-" + strings.ReplaceAll(host, ".", `\.`) + "$`)"
}

// buildIngressRoute renders the traefik.io/v1alpha1 IngressRoute as an
// unstructured object for the dynamic client.
func (g *Gateway) buildIngressRoute(namespace, name string, ownerRef metav1.OwnerReference) *unstructured.Unstructured {
	host := name + "." + g.opts.Domain
	entryPoint := "web"
	if g.opts.HTTPS {
		entryPoint = "websecure"
	}
	middlewares := make([]any, 0, len(g.opts.Middlewares))
	for _, middleware := range g.opts.Middlewares {
		middlewares = append(middlewares, map[string]any{"name": middleware})
	}
	spec := map[string]any{
		"entryPoints": []any{entryPoint},
		"routes": []any{map[string]any{
			"match":       ingressMatchRule(host),
			"syntax":      "v3",
			"kind":        "Rule",
			"services":    []any{map[string]any{"name": name, "port": int64(80)}},
			"middlewares": middlewares,
		}},
	}
	if g.opts.HTTPS {
		spec["tls"] = map[string]any{"certResolver": g.opts.CertResolver}
	}

	route := &unstructured.Unstructured{Object: map[string]any{
		"apiVersion": ingressRouteGVR.Group + "/" + ingressRouteGVR.Version,
		"kind":       "IngressRoute",
		"metadata": map[string]any{
			"name":      name,
			"namespace": namespace,
			"ownerReferences": []any{map[string]any{
				"apiVersion":         ownerRef.APIVersion,
				"kind":               ownerRef.Kind,
				"name":               ownerRef.Name,
				"uid":                string(ownerRef.UID),
				"controller":         true,
				"blockOwnerDeletion": true,
			}},
		},
		"spec": spec,
	}}
	return route
}

// createIngressRoute skips (with a warning) when the Traefik CRD is absent.
func (g *Gateway) createIngressRoute(ctx context.Context, dynamicClient dynamic.Interface, namespace, name string, ownerRef metav1.OwnerReference) error {
	if _, err := dynamicClient.Resource(crdGVR).Get(ctx, ingressRouteCRDName, metav1.GetOptions{}); err != nil {
		if k8s.IsNotFound(err) {
			slog.Warn("Could not find IngressRoute CRD, skipping ingress route creation for IDE", "name", name)
			return nil
		}
		return err
	}
	route := g.buildIngressRoute(namespace, name, ownerRef)
	return serverSideApply(ctx, route.Object, func(payload []byte) error {
		_, applyErr := dynamicClient.Resource(ingressRouteGVR).Namespace(namespace).Patch(ctx, name, types.ApplyPatchType, payload, applyOptions())
		return applyErr
	})
}

func copyMap(source map[string]string) map[string]string {
	copied := make(map[string]string, len(source))
	for key, value := range source {
		copied[key] = value
	}
	return copied
}

func isBlank(value string) bool { return strings.TrimSpace(value) == "" }
