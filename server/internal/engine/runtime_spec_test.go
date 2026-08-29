package engine

import (
	"testing"

	corev1 "k8s.io/api/core/v1"

	"github.com/wellch4n/oops/server/internal/store"
)

func text(value string) *string { return &value }
func count(value int) *int      { return &value }

// A replica change is the whole point of the immediate apply: the Go rewrite
// dropped ApplicationService.applyRuntimeSpecEnvironmentConfigUpdates, so a
// saved replica count only reached the cluster on the next publish.
func TestRuntimeSpecNeedsApplyOnReplicaChange(t *testing.T) {
	existing := &store.RuntimeEnvironmentConfig{Replicas: count(1)}
	if !runtimeSpecNeedsApply(&store.RuntimeEnvironmentConfig{Replicas: count(3)}, existing) {
		t.Error("scaling up must apply")
	}
	if !runtimeSpecNeedsApply(&store.RuntimeEnvironmentConfig{Replicas: count(0)}, existing) {
		t.Error("scaling to zero must apply")
	}
	if runtimeSpecNeedsApply(&store.RuntimeEnvironmentConfig{Replicas: count(1)}, existing) {
		t.Error("an unchanged replica count must not apply")
	}
	// No stored config at all: the first save of a replica count still applies.
	if !runtimeSpecNeedsApply(&store.RuntimeEnvironmentConfig{Replicas: count(2)}, nil) {
		t.Error("a first replica count must apply")
	}
	// A payload carrying no replica count says nothing about scale.
	if runtimeSpecNeedsApply(&store.RuntimeEnvironmentConfig{}, existing) {
		t.Error("an absent replica count must not apply")
	}
}

func TestRuntimeSpecNeedsApplyOnResourceChange(t *testing.T) {
	existing := &store.RuntimeEnvironmentConfig{CPULimit: text("2"), MemoryLimit: text("512")}
	changed := &store.RuntimeEnvironmentConfig{CPULimit: text("4"), MemoryLimit: text("512")}
	if !runtimeSpecNeedsApply(changed, existing) {
		t.Error("a changed cpu limit must apply")
	}
	same := &store.RuntimeEnvironmentConfig{CPULimit: text("2"), MemoryLimit: text("512")}
	if runtimeSpecNeedsApply(same, existing) {
		t.Error("identical resources must not apply")
	}
	// The form posts "" for a quantity the stored spec never carried; that is
	// not a change and must not cost a cluster round trip.
	blank := &store.RuntimeEnvironmentConfig{CPURequest: text("")}
	if runtimeSpecNeedsApply(blank, &store.RuntimeEnvironmentConfig{}) {
		t.Error("blank against absent must not apply")
	}
}

func TestRuntimeResourceRequirements(t *testing.T) {
	resources, present := runtimeResourceRequirements(&store.RuntimeEnvironmentConfig{
		CPURequest: text("0.5"), CPULimit: text("2"),
		MemoryRequest: text("256"), MemoryLimit: text("512"),
	})
	if !present {
		t.Fatal("resources must be reported present")
	}
	if got := resources.Requests[corev1.ResourceCPU]; got.String() != "500m" {
		t.Errorf("cpu request = %s", got.String())
	}
	// Memory quantities are stored bare and mean mebibytes.
	if got := resources.Limits[corev1.ResourceMemory]; got.String() != "512Mi" {
		t.Errorf("memory limit = %s", got.String())
	}

	if _, present := runtimeResourceRequirements(&store.RuntimeEnvironmentConfig{}); present {
		t.Error("an empty spec must report no resources")
	}
	// A quantity the cluster would reject is skipped, not panicked on: this
	// now runs inside a request thread, where MustParse would take it down.
	if _, present := runtimeResourceRequirements(&store.RuntimeEnvironmentConfig{CPULimit: text("two")}); present {
		t.Error("an unparsable quantity must be dropped")
	}
}
