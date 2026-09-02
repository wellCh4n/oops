// Package sandbox is the Kubernetes adapter behind /api/sandbox: throwaway
// Jobs for one-shot script executions and StatefulSets for long-lived
// instances, mirroring KubernetesSandboxExecutionGateway + AlpineMateTemplate.
package sandbox

import (
	"regexp"
	"sort"
	"strings"

	"github.com/wellch4n/oops/server/internal/k8s"
)

// Labels, annotations and names shared by every sandbox resource.
const (
	LabelType      = "oops.type"
	LabelSandboxID = "oops.sandbox.id"
	LabelKind      = "oops.sandbox.kind"
	LabelCreatedBy = "oops.sandbox.created-by"
	LabelImage     = "oops.sandbox.image"

	AnnotationName          = "oops.sandbox.name"
	AnnotationImage         = "oops.sandbox.image"
	AnnotationCPURequest    = "oops.sandbox.cpu-request"
	AnnotationCPULimit      = "oops.sandbox.cpu-limit"
	AnnotationMemoryRequest = "oops.sandbox.memory-request"
	AnnotationMemoryLimit   = "oops.sandbox.memory-limit"

	TypeValue      = "sandbox"
	KindEphemeral  = "ephemeral"
	KindPersistent = "persistent"

	NamePrefix    = "oops-sandbox-"
	ContainerName = "sandbox"

	persistentKeepaliveCommand = "trap : TERM INT; sleep infinity & wait"
	binSh                      = "/bin/sh"
	imagePullSecretName        = "dockerhub"
	maxLabelValueLength        = 63
)

// Builtin runtimes: a key the user picks instead of an image reference.
const (
	builtinAlpineMateKey   = "alpine-mate"
	builtinAlpineMateImage = "linuxserver/webtop:alpine-mate"
)

var builtinRuntimeImages = map[string]string{
	builtinAlpineMateKey: builtinAlpineMateImage,
}

// BuiltinRuntimes returns the sorted keys of the builtin runtimes.
func BuiltinRuntimes() []string {
	keys := make([]string, 0, len(builtinRuntimeImages))
	for key := range builtinRuntimeImages {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
}

// IsBuiltin reports whether image is a builtin runtime key (exact match).
func IsBuiltin(image string) bool {
	_, ok := builtinRuntimeImages[image]
	return ok
}

// EnvVar is one environment variable; a slice keeps the caller's insertion order.
type EnvVar struct {
	Name  string
	Value string
}

// JobSpec describes one ephemeral execution (SandboxJobSpec).
type JobSpec struct {
	Image                   string
	Script                  string
	TimeoutSeconds          int
	TTLSecondsAfterFinished int
	CPURequest              string
	CPULimit                string
	MemoryRequest           string
	MemoryLimit             string
	Env                     []EnvVar
	CreatedByUserID         string
}

// PersistentSpec describes one long-lived instance (PersistentSandboxSpec).
// Resource fields are optional and omitted from the pod spec when nil.
type PersistentSpec struct {
	SandboxID           string
	Name                string
	Image               string
	CPURequest          *string
	CPULimit            *string
	MemoryRequest       *string
	MemoryLimit         *string
	Env                 []EnvVar
	CreatedByUserID     string
	UseDefaultKeepalive bool
}

// ExecResult is the SandboxExecutionResult envelope payload.
type ExecResult struct {
	ExitCode int    `json:"exitCode"`
	Output   string `json:"output"`
}

// Gateway runs sandboxes against whichever cluster the caller names.
type Gateway struct {
	pool *k8s.Pool
}

// New builds a Gateway over the shared client pool.
func New(pool *k8s.Pool) *Gateway {
	return &Gateway{pool: pool}
}

var (
	labelValueInvalidChars = regexp.MustCompile(`[^A-Za-z0-9._-]`)
	labelValueEdgeTrim     = regexp.MustCompile(`^[^A-Za-z0-9]+|[^A-Za-z0-9]+$`)
)

// SanitizeLabelValue turns an arbitrary string (typically an image reference)
// into a valid Kubernetes label value: invalid characters become "_", the
// result is truncated to 63 characters, then non-alphanumeric characters are
// stripped from both ends.
func SanitizeLabelValue(value string) string {
	if value == "" {
		return ""
	}
	sanitized := labelValueInvalidChars.ReplaceAllString(value, "_")
	if len(sanitized) > maxLabelValueLength {
		sanitized = sanitized[:maxLabelValueLength]
	}
	return labelValueEdgeTrim.ReplaceAllString(sanitized, "")
}

// StatefulSetName / JobName both follow the same "oops-sandbox-<id>" scheme.
func resourceName(sandboxID string) string { return NamePrefix + sandboxID }

// PodName returns the single pod of a persistent instance.
func PodName(sandboxID string) string { return resourceName(sandboxID) + "-0" }

func isBlank(value string) bool { return strings.TrimSpace(value) == "" }
