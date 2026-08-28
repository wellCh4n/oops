package domain

import (
	"errors"
	"strings"
	"testing"
)

// Mirrors PipelineStateMachineTests.
func TestPipelineTransitions(t *testing.T) {
	allowed := [][2]string{
		{PipelineInitialized, PipelineRunning},
		{PipelineInitialized, PipelineDeploying}, // rollback skips the build
		{PipelineRunning, PipelineBuildSucceeded},
		{PipelineRunning, PipelineDeploying},
		{PipelineRunning, PipelineError},
		{PipelineRunning, PipelineStopped},
		{PipelineBuildSucceeded, PipelineDeploying},
		{PipelineDeploying, PipelineRollingOut},
		{PipelineDeploying, PipelineError},
		{PipelineRollingOut, PipelineSucceeded},
		{PipelineRollingOut, PipelineError},
	}
	for _, transition := range allowed {
		if err := EnsurePipelineTransition(transition[0], transition[1]); err != nil {
			t.Errorf("%s -> %s should be allowed: %v", transition[0], transition[1], err)
		}
	}

	forbidden := [][2]string{
		{PipelineSucceeded, PipelineRunning}, // terminal states are final
		{PipelineError, PipelineDeploying},
		{PipelineStopped, PipelineRunning},
		{PipelineInitialized, PipelineSucceeded}, // no skipping ahead
		{PipelineRunning, PipelineSucceeded},
		{PipelineDeploying, PipelineSucceeded},
	}
	for _, transition := range forbidden {
		if err := EnsurePipelineTransition(transition[0], transition[1]); err == nil {
			t.Errorf("%s -> %s should be rejected", transition[0], transition[1])
		}
	}
}

func TestPipelineTerminalStatuses(t *testing.T) {
	for _, status := range []string{PipelineSucceeded, PipelineError, PipelineStopped} {
		if !IsPipelineTerminal(status) {
			t.Errorf("%s should be terminal", status)
		}
	}
	for _, status := range []string{PipelineInitialized, PipelineRunning, PipelineBuildSucceeded, PipelineDeploying, PipelineRollingOut} {
		if IsPipelineTerminal(status) {
			t.Errorf("%s should not be terminal", status)
		}
	}
}

// Mirrors DeploymentConcurrencyPolicyTests: the duplicate-deploy guard treats
// exactly these statuses as in-flight.
func TestActivePipelineStatuses(t *testing.T) {
	want := []string{PipelineRunning, PipelineDeploying, PipelineRollingOut}
	if len(ActivePipelineStatuses) != len(want) {
		t.Fatalf("active statuses = %v, want %v", ActivePipelineStatuses, want)
	}
	for i, status := range want {
		if ActivePipelineStatuses[i] != status {
			t.Errorf("active statuses = %v, want %v", ActivePipelineStatuses, want)
		}
	}
}

// Mirrors ResourceNameCheckerTests.
func TestIsValidResourceName(t *testing.T) {
	valid := []string{"a", "my-app", "app1", "a1-b2-c3", strings.Repeat("a", 24)}
	for _, name := range valid {
		if !IsValidResourceName(name) {
			t.Errorf("%q should be valid", name)
		}
	}
	invalid := []string{"", "-app", "app-", "1app", "App", "my_app", "my.app", strings.Repeat("a", 25)}
	for _, name := range invalid {
		if IsValidResourceName(name) {
			t.Errorf("%q should be invalid", name)
		}
	}
}

// Mirrors DomainPolicyTests host validation and matching.
func TestNormalizeAndValidateHost(t *testing.T) {
	// normalizeHost only trims and strips a "*." prefix — it never lowercases;
	// uppercase input is rejected by validation instead.
	if got := NormalizeHost("  *.example.com "); got != "example.com" {
		t.Errorf("NormalizeHost = %q", got)
	}
	for _, host := range []string{"example.com", "a.b.example.com", "*.example.com", "xn--fiq228c.cn"} {
		if err := ValidateHost(NormalizeHost(host)); err != nil {
			t.Errorf("%q should validate: %v", host, err)
		}
	}
	for _, host := range []string{"", "Example.COM", "no spaces.com", "-bad.com", "exa_mple.com", "single-label"} {
		if err := ValidateHost(NormalizeHost(host)); err == nil {
			t.Errorf("%q should be rejected", host)
		}
	}
}

func TestHostCoveredBy(t *testing.T) {
	cases := []struct {
		full, domain string
		want         bool
	}{
		{"app.example.com", "example.com", true},
		{"example.com", "example.com", true},
		{"a.b.example.com", "example.com", true},
		// Wildcards are stripped by NormalizeHost before storage, so matching
		// only ever sees plain hosts.
		{"app.example.com", NormalizeHost("*.example.com"), true},
		{"app.example.org", "example.com", false},
		{"badexample.com", "example.com", false}, // suffix must be label-aligned
	}
	for _, c := range cases {
		if got := HostCoveredBy(c.full, c.domain); got != c.want {
			t.Errorf("HostCoveredBy(%q, %q) = %v, want %v", c.full, c.domain, got, c.want)
		}
	}
}

func TestBizError(t *testing.T) {
	err := Bizf("Application is being deployed")
	if !IsBizError(err) {
		t.Error("Bizf should produce a BizError")
	}
	var bizError *BizError
	if !errors.As(err, &bizError) || bizError.Message != "Application is being deployed" {
		t.Errorf("unexpected error: %v", err)
	}
	if IsBizError(errors.New("plain")) {
		t.Error("plain error must not be a BizError")
	}
}

// The NanoId contract every entity key relies on: 24 chars, lowercase alnum.
func TestNewID(t *testing.T) {
	seen := map[string]bool{}
	for range 100 {
		id := NewID()
		if len(id) != 24 {
			t.Fatalf("id %q length = %d", id, len(id))
		}
		for _, r := range id {
			if (r < 'a' || r > 'z') && (r < '0' || r > '9') {
				t.Fatalf("id %q has invalid rune %q", id, r)
			}
		}
		if seen[id] {
			t.Fatalf("duplicate id %q", id)
		}
		seen[id] = true
	}
}
