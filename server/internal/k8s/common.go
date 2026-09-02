package k8s

import (
	"context"
	"encoding/json"
	"strings"
	"time"

	"github.com/wellch4n/oops/server/internal/domain"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/kubernetes"
)

// FieldManager is the server-side-apply manager name used for every write.
const FieldManager = "oops"

const (
	// ServicePort is the port every application Service listens on.
	ServicePort = 80

	// ControllerRevisionLabel is stamped on StatefulSet pods by Kubernetes.
	ControllerRevisionLabel = "controller-revision-hash"

	// RolloutStartedAtAnnotation marks when the StatefulSet template was last applied by a deploy.
	RolloutStartedAtAnnotation = "oops.rollout.started-at"
	// PipelineIDAnnotation records the pipeline that produced the current template.
	PipelineIDAnnotation = "oops.pipeline.id"
	// RestartedAtAnnotation is the kubectl-compatible rolling restart trigger.
	RestartedAtAnnotation = "kubectl.kubernetes.io/restartedAt"

	// MountAnnotation and ConfigMetaAnnotation carry the config editor's UI metadata.
	MountAnnotation      = "oops.mounts"
	ConfigMetaAnnotation = "oops.config-meta"
	// FilesResourceSuffix names the ConfigMap/Secret holding mounted file items.
	FilesResourceSuffix = ".files"

	// ImagePullSecretName is the registry credential Secret name in every namespace.
	ImagePullSecretName = "dockerhub"
	// GitCredentialSecretName is the git credential Secret in the work namespace.
	GitCredentialSecretName = "git-credential"

	// RedirectMiddlewareName is the namespace-shared HTTP->HTTPS redirect Middleware.
	RedirectMiddlewareName = "oops-redirect-https"
	// BasicAuthLabelKey / BasicAuthLabelValue mark basic-auth Secrets and Middlewares.
	BasicAuthLabelKey   = "oops.resource"
	BasicAuthLabelValue = "basic-auth"

	// DefaultServiceAccount is written when the expert config clears the service account.
	DefaultServiceAccount = "default"
)

// Traefik CRD coordinates.
var (
	IngressRouteGVR = schema.GroupVersionResource{Group: "traefik.io", Version: "v1alpha1", Resource: "ingressroutes"}
	MiddlewareGVR   = schema.GroupVersionResource{Group: "traefik.io", Version: "v1alpha1", Resource: "middlewares"}
	crdGVR          = schema.GroupVersionResource{Group: "apiextensions.k8s.io", Version: "v1", Resource: "customresourcedefinitions"}

	ingressRouteCRDName = "ingressroutes.traefik.io"
	traefikAPIVersion   = "traefik.io/v1alpha1"
)

// ApplicationLabels returns the two labels stamped on every application resource.
func ApplicationLabels(applicationName string) map[string]string {
	return map[string]string{
		domain.LabelType:    domain.TypeApplication,
		domain.LabelAppName: applicationName,
	}
}

// ApplicationPodSelector is the label selector for an application's pods.
func ApplicationPodSelector(applicationName string) string {
	return domain.LabelType + "=" + domain.TypeApplication + "," + domain.LabelAppName + "=" + applicationName
}

// ApplicationNameSelector selects Services/IngressRoutes by application name only.
func ApplicationNameSelector(applicationName string) string {
	return domain.LabelAppName + "=" + applicationName
}

// applyOptions returns the PatchOptions for server-side apply.
func applyOptions(force bool) metav1.PatchOptions {
	options := metav1.PatchOptions{FieldManager: FieldManager}
	if force {
		options.Force = domain.Ptr(true)
	}
	return options
}

var (
	forceApply   = applyOptions(true)
	noForceApply = applyOptions(false)
)

// applyPatch serializes a typed object into an apply patch body. Status and the
// null creationTimestamp are stripped so the patch only carries desired state.
func applyPatch(object runtime.Object) ([]byte, error) {
	content, err := runtime.DefaultUnstructuredConverter.ToUnstructured(object)
	if err != nil {
		return nil, err
	}
	delete(content, "status")
	if metadata, ok := content["metadata"].(map[string]any); ok {
		delete(metadata, "creationTimestamp")
	}
	return json.Marshal(content)
}

// applyUnstructured serializes an unstructured object for server-side apply.
func applyUnstructured(object *unstructured.Unstructured) ([]byte, error) {
	return json.Marshal(object.Object)
}

// applyPatchType is the content type for server-side apply.
const applyPatchType = types.ApplyPatchType

// StatefulSetOwnerReference builds the controller owner reference every
// dependent application resource carries.
func StatefulSetOwnerReference(applicationName string, uid types.UID) metav1.OwnerReference {
	return metav1.OwnerReference{
		APIVersion:         "apps/v1",
		Kind:               "StatefulSet",
		Name:               applicationName,
		UID:                uid,
		Controller:         domain.Ptr(true),
		BlockOwnerDeletion: domain.Ptr(true),
	}
}

// findStatefulSetOwnerReference looks the application's StatefulSet up and
// returns its owner reference, or nil when it does not exist yet.
func findStatefulSetOwnerReference(ctx context.Context, clientset kubernetes.Interface, namespace, applicationName string) (*metav1.OwnerReference, error) {
	statefulSet, err := clientset.AppsV1().StatefulSets(namespace).Get(ctx, applicationName, metav1.GetOptions{})
	if err != nil {
		if IsNotFound(err) {
			return nil, nil
		}
		return nil, err
	}
	reference := StatefulSetOwnerReference(applicationName, statefulSet.UID)
	return &reference, nil
}

// isBlank reports whether the string is empty or whitespace only.
func isBlank(value string) bool { return strings.TrimSpace(value) == "" }

// blankPtr reports whether the pointer is nil or points at a blank string.
func blankPtr(value *string) bool { return domain.IsBlank(value) }

// dashHost replaces dots with dashes, the way Traefik resource names are derived from hosts.
func dashHost(host string) string { return strings.ReplaceAll(host, ".", "-") }

// formatInstant renders a time the way Java's Instant.toString does: RFC3339
// UTC with the fraction trimmed in groups of three digits.
func formatInstant(instant time.Time) string {
	utc := instant.UTC()
	base := utc.Format("2006-01-02T15:04:05")
	nanos := utc.Nanosecond()
	if nanos == 0 {
		return base + "Z"
	}
	fraction := utc.Format(".000000000")
	for strings.HasSuffix(fraction, "000") && len(fraction) > 4 {
		fraction = fraction[:len(fraction)-3]
	}
	return base + fraction + "Z"
}

// parseInstant parses an RFC3339 instant, returning nil when it does not parse.
func parseInstant(value string) *time.Time {
	value = strings.TrimSpace(value)
	if value == "" {
		return nil
	}
	parsed, err := time.Parse(time.RFC3339Nano, value)
	if err != nil {
		return nil
	}
	return &parsed
}

// podIsTerminating mirrors PodStates.isTerminating.
func podIsTerminating(pod *corev1.Pod) bool { return pod.DeletionTimestamp != nil }

// podIsRunningAndReady mirrors PodStates.isRunningAndReady.
func podIsRunningAndReady(pod *corev1.Pod) bool {
	if pod.Status.Phase != corev1.PodRunning {
		return false
	}
	for _, condition := range pod.Status.Conditions {
		if condition.Type == corev1.PodReady && strings.EqualFold(string(condition.Status), "True") {
			return true
		}
	}
	return false
}

// podIsAtRevision mirrors PodStates.isAtRevision (false when revision is blank).
func podIsAtRevision(pod *corev1.Pod, revision string) bool {
	if revision == "" {
		return false
	}
	return pod.Labels[ControllerRevisionLabel] == revision
}
