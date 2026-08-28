package engine

import (
	"testing"

	"github.com/wellch4n/oops/server/internal/store"
)

// Mirrors ApplicationPriorityTests.
func TestPriorityClassNameOf(t *testing.T) {
	high := "HIGH"
	if name, value := priorityClassNameOf(&high); name != "oops-high-priority" || value != 1_000_000 {
		t.Errorf("HIGH -> %s/%d", name, value)
	}
	low := "low" // case-insensitive
	if name, value := priorityClassNameOf(&low); name != "oops-low-priority" || value != -1_000_000 {
		t.Errorf("low -> %s/%d", name, value)
	}
	normal := "NORMAL"
	if name, _ := priorityClassNameOf(&normal); name != "" {
		t.Errorf("NORMAL must map to no priority class, got %s", name)
	}
	if name, _ := priorityClassNameOf(nil); name != "" {
		t.Errorf("nil must map to no priority class, got %s", name)
	}
}

func TestDistinctInternalPorts(t *testing.T) {
	config := &store.ServiceConfigView{InternalPorts: []int{9090, 8081, 9090, 8081}}
	ports := distinctInternalPorts(config)
	if len(ports) != 2 || ports[0] != 9090 || ports[1] != 8081 {
		t.Errorf("ports = %v", ports)
	}
	if ports := distinctInternalPorts(nil); ports == nil || len(ports) != 0 {
		t.Errorf("nil config must give empty (non-nil) slice, got %v", ports)
	}
}

// Mirrors ProbeTests probe rendering.
func TestProbeFor(t *testing.T) {
	enabled, path, delay := true, "/healthz", 5
	probe := probeFor(&store.Probe{Enabled: &enabled, Path: &path, InitialDelaySeconds: &delay}, 8080)
	if probe.HTTPGet.Path != "/healthz" || probe.HTTPGet.Port.IntValue() != 8080 {
		t.Errorf("http get = %+v", probe.HTTPGet)
	}
	if probe.InitialDelaySeconds != 5 || probe.PeriodSeconds != 10 || probe.TimeoutSeconds != 3 || probe.FailureThreshold != 3 {
		t.Errorf("timings = %d/%d/%d/%d", probe.InitialDelaySeconds, probe.PeriodSeconds, probe.TimeoutSeconds, probe.FailureThreshold)
	}

	empty := probeFor(&store.Probe{}, 80)
	if empty.HTTPGet.Path != "/" || empty.InitialDelaySeconds != 30 {
		t.Errorf("defaults = %+v", empty)
	}
}

func TestProbeEnabled(t *testing.T) {
	enabled, disabled := true, false
	if probeEnabled(nil) || probeEnabled(&store.Probe{}) || probeEnabled(&store.Probe{Enabled: &disabled}) {
		t.Error("nil/unset/false must be disabled")
	}
	if !probeEnabled(&store.Probe{Enabled: &enabled}) {
		t.Error("true must be enabled")
	}
}
