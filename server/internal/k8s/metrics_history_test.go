package k8s

import (
	"strings"
	"testing"
	"time"
)

// Mirrors PrometheusPodMetricHistoryProviderTests selector building: names are
// validated before interpolation into PromQL.
func TestPodSelector(t *testing.T) {
	selector, err := podSelector("default", "demo")
	if err != nil {
		t.Fatal(err)
	}
	for _, fragment := range []string{`namespace="default"`, `pod=~"demo-[0-9]+"`, `container!=""`} {
		if !strings.Contains(selector, fragment) {
			t.Errorf("selector missing %s: %s", fragment, selector)
		}
	}
}

func TestPodSelectorRejectsInjection(t *testing.T) {
	injections := []string{`x",namespace="kube-system`, "a b", "UPPER", "", "app_name"}
	for _, bad := range injections {
		if _, err := podSelector("default", bad); err == nil {
			t.Errorf("application name %q must be rejected", bad)
		}
		if _, err := podSelector(bad, "demo"); err == nil {
			t.Errorf("namespace %q must be rejected", bad)
		}
	}
}

// Mirrors PodMetricHistoryServiceTests range parsing.
func TestParseMetricsRange(t *testing.T) {
	cases := []struct {
		spec string
		want time.Duration
	}{
		{"", time.Hour}, // default window
		{"30m", 30 * time.Minute},
		{"1h", time.Hour},
		{"6h", 6 * time.Hour},
		{"48h", 24 * time.Hour}, // clamped to the configured max
	}
	for _, c := range cases {
		got, err := parseMetricsRange(c.spec, 24)
		if err != nil {
			t.Errorf("parseMetricsRange(%q): %v", c.spec, err)
			continue
		}
		if got != c.want {
			t.Errorf("parseMetricsRange(%q) = %v, want %v", c.spec, got, c.want)
		}
	}
	for _, bad := range []string{"abc", "-5m", "0h", "1d", "5"} {
		if _, err := parseMetricsRange(bad, 24); err == nil {
			t.Errorf("%q must be rejected", bad)
		}
	}
}

func TestMonitoringErrorDetection(t *testing.T) {
	_, err := parseMetricsRange("bogus", 24)
	if !IsMonitoringError(err) {
		t.Error("range errors are monitoring errors (rendered as setup prompt)")
	}
}
