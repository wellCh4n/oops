// Package-level sandbox gateway, the Go counterpart of
// infrastructure/kubernetes/sandbox.
package k8s

import (
	"regexp"
	"sort"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
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
