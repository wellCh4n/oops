package engine

import "testing"

// Mirrors ResourceAlertScanJobTests limit parsing: an application without
// parseable limits is skipped, never alerted on.

func TestParseCPUMillis(t *testing.T) {
	cases := []struct {
		value string
		want  int64
		ok    bool
	}{
		{"500m", 500, true},
		{"2", 2000, true},
		{"0.5", 500, true},
		{" 1 ", 1000, true},
		{"", 0, false},
		{"abc", 0, false},
		{"m", 0, false},
	}
	for _, c := range cases {
		got, ok := parseCPUMillis(c.value)
		if ok != c.ok || (ok && got != c.want) {
			t.Errorf("parseCPUMillis(%q) = %d,%v want %d,%v", c.value, got, ok, c.want, c.ok)
		}
	}
}

func TestParseMemoryBytes(t *testing.T) {
	cases := []struct {
		value string
		want  int64
		ok    bool
	}{
		{"512Mi", 512 * 1024 * 1024, true},
		{"512", 512 * 1024 * 1024, true}, // write path stores plain Mi numbers
		{"1.5Mi", 1572864, true},
		{"", 0, false},
		{"abcMi", 0, false},
	}
	for _, c := range cases {
		got, ok := parseMemoryBytes(c.value)
		if ok != c.ok || (ok && got != c.want) {
			t.Errorf("parseMemoryBytes(%q) = %d,%v want %d,%v", c.value, got, ok, c.want, c.ok)
		}
	}
}
