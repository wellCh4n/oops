package engine

import (
	"testing"

	"github.com/wellch4n/oops/server/internal/store"
)

// The Go rewrite dropped ApplicationService.applyExpertConfigUpdates too, so
// a changed ServiceAccount, priority or node pinning only reached the cluster
// on the next publish.
func TestExpertConfigNeedsApply(t *testing.T) {
	existing := &store.ExpertEnvironmentConfig{
		ServiceAccountName: text("builder"),
		Priority:           text("HIGH"),
		NodeNames:          []string{"node-a", "node-b"},
	}
	unchanged := &store.ExpertEnvironmentConfig{
		ServiceAccountName: text("builder"),
		Priority:           text("HIGH"),
		// Same set, different order: not a change.
		NodeNames: []string{"node-b", "node-a"},
	}
	if expertConfigNeedsApply(unchanged, existing) {
		t.Error("an unchanged config must not apply")
	}

	serviceAccount := *unchanged
	serviceAccount.ServiceAccountName = text("deployer")
	if !expertConfigNeedsApply(&serviceAccount, existing) {
		t.Error("a changed service account must apply")
	}

	cleared := *unchanged
	cleared.NodeNames = nil
	if !expertConfigNeedsApply(&cleared, existing) {
		t.Error("clearing node pinning must apply")
	}

	priority := *unchanged
	priority.Priority = text("LOW")
	if !expertConfigNeedsApply(&priority, existing) {
		t.Error("a changed priority must apply")
	}

	// NORMAL, an unknown tier and no tier at all all resolve to no
	// PriorityClass, so switching between them is not a workload change.
	normal := &store.ExpertEnvironmentConfig{Priority: text("NORMAL")}
	if expertConfigNeedsApply(normal, &store.ExpertEnvironmentConfig{}) {
		t.Error("NORMAL against no priority must not apply")
	}

	// The scheduled-restart fields are read by the minute scan, never written
	// onto the workload, so touching them must not roll the pods.
	restart := *unchanged
	restart.ScheduledRestartEnabled = true
	restart.ScheduledRestartCron = text("0 3 * * *")
	if expertConfigNeedsApply(&restart, existing) {
		t.Error("scheduled-restart fields must not trigger a workload apply")
	}
}

func TestNormalizedNodeNames(t *testing.T) {
	names := normalizedNodeNames([]string{" node-b ", "node-a", "", "node-b", "   "})
	if len(names) != 2 || names[0] != "node-a" || names[1] != "node-b" {
		t.Errorf("normalized = %v", names)
	}
	if names := normalizedNodeNames(nil); len(names) != 0 {
		t.Errorf("nil must normalize to empty, got %v", names)
	}
	if !sameNodeNames(nil, []string{"", "  "}) {
		t.Error("nil and an all-blank list both mean no constraint")
	}
}
