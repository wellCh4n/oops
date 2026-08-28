package store

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"math/big"
	"strings"
	"testing"
	"time"
)

// Mirrors ProbeTests + HealthCheckPolicyTests: absent JSON keys pick up the
// Java entity initializers so both backends render identical probes.
func TestNormalizeProbeDefaults(t *testing.T) {
	probe := &Probe{}
	normalizeProbe(probe)
	if *probe.Enabled != false || *probe.Path != "/" {
		t.Errorf("enabled=%v path=%v", *probe.Enabled, *probe.Path)
	}
	if *probe.InitialDelaySeconds != 30 || *probe.PeriodSeconds != 10 ||
		*probe.TimeoutSeconds != 3 || *probe.FailureThreshold != 3 {
		t.Errorf("timings = %d/%d/%d/%d", *probe.InitialDelaySeconds, *probe.PeriodSeconds,
			*probe.TimeoutSeconds, *probe.FailureThreshold)
	}
}

func TestNormalizeProbeKeepsStoredValues(t *testing.T) {
	enabled, path, delay := true, "/healthz", 5
	probe := &Probe{Enabled: &enabled, Path: &path, InitialDelaySeconds: &delay}
	normalizeProbe(probe)
	if !*probe.Enabled || *probe.Path != "/healthz" || *probe.InitialDelaySeconds != 5 {
		t.Error("stored values must not be overwritten")
	}
	if *probe.PeriodSeconds != 10 {
		t.Error("absent fields must still be defaulted")
	}
}

func TestDefaultRuntimeSpec(t *testing.T) {
	spec := DefaultRuntimeSpec("default", "demo")
	if spec.HealthCheck == nil || spec.HealthCheck.Liveness == nil || spec.HealthCheck.Readiness == nil {
		t.Fatal("default spec must carry both probes")
	}
	if *spec.HealthCheck.Liveness.Enabled {
		t.Error("default probes are disabled")
	}
	if spec.EnvironmentConfigs == nil || len(spec.EnvironmentConfigs) != 0 {
		t.Error("environment configs default to an empty list, not null")
	}
}

// Mirrors the @AttributeConverter tests: JSONField keeps the null-vs-[]
// distinction through a scan/value round trip.
func TestJSONFieldRoundTrip(t *testing.T) {
	field := jsonOf([]string{"a", "b"})
	value, err := field.Value()
	if err != nil {
		t.Fatal(err)
	}
	var restored JSONField[[]string]
	if err := restored.Scan(value); err != nil {
		t.Fatal(err)
	}
	if !restored.Valid || len(restored.Data) != 2 {
		t.Errorf("round trip lost data: %+v", restored)
	}
}

func TestJSONFieldNull(t *testing.T) {
	var field JSONField[[]string]
	if value, _ := field.Value(); value != nil {
		t.Error("invalid field must store NULL")
	}
	if err := field.Scan(nil); err != nil || field.Valid {
		t.Error("NULL column must scan to Valid=false")
	}
	// An empty JSON array is not NULL: "[]" must stay distinguishable.
	if err := field.Scan([]byte("[]")); err != nil || !field.Valid || field.Data == nil {
		t.Errorf("[] must scan Valid with empty slice: %+v", field)
	}
}

// LocalDateTime renders Java's LocalDateTime shape: local time, no zone.
func TestLocalDateTimeMarshal(t *testing.T) {
	instant := time.Date(2026, 8, 28, 9, 30, 15, 123456000, time.Local)
	encoded, err := json.Marshal(LocalDateTime{Time: instant})
	if err != nil {
		t.Fatal(err)
	}
	text := string(encoded)
	if text != `"2026-08-28T09:30:15.123456"` {
		t.Errorf("marshal = %s", text)
	}
	if strings.ContainsAny(text, "Z+") {
		t.Errorf("must not carry a zone marker: %s", text)
	}
}

// The wall clock in the column is rendered verbatim, never re-zoned: the
// datetime(6) columns already hold local time, so converting on output would
// push every Java-era row forward by the local offset.
func TestLocalDateTimeMarshalDoesNotConvert(t *testing.T) {
	instant := time.Date(2026, 8, 28, 9, 30, 15, 123456000, time.UTC)
	encoded, err := json.Marshal(LocalDateTime{Time: instant})
	if err != nil {
		t.Fatal(err)
	}
	if string(encoded) != `"2026-08-28T09:30:15.123456"` {
		t.Errorf("marshal must render the value's own wall clock, got %s", encoded)
	}
}

// Mirrors PemCertificateParserTests, with a certificate minted on the fly.
func testCertificate(t *testing.T, commonName string, dnsNames []string) string {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: commonName},
		DNSNames:     dnsNames,
		NotBefore:    time.Now(),
		NotAfter:     time.Now().Add(24 * time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, &template, &template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}))
}

func TestParseCertificate(t *testing.T) {
	meta, err := parseCertificate(testCertificate(t, "example.com", []string{"example.com", "*.example.com"}))
	if err != nil {
		t.Fatal(err)
	}
	if len(meta.dnsNames) != 2 {
		t.Errorf("dnsNames = %v", meta.dnsNames)
	}

	// No SAN: fall back to the CN.
	meta, err = parseCertificate(testCertificate(t, "cn-only.example.com", nil))
	if err != nil {
		t.Fatal(err)
	}
	if len(meta.dnsNames) != 1 || meta.dnsNames[0] != "cn-only.example.com" {
		t.Errorf("CN fallback failed: %v", meta.dnsNames)
	}

	if _, err := parseCertificate("not a pem"); err == nil {
		t.Error("garbage must be rejected")
	}
}

func TestCertificateHostMatches(t *testing.T) {
	cases := []struct {
		host string
		dns  []string
		want bool
	}{
		{"example.com", []string{"example.com"}, true},
		{"app.example.com", []string{"*.example.com"}, true},
		{"example.com", []string{"*.example.com"}, true},      // apex covered by wildcard
		{"a.b.example.com", []string{"*.example.com"}, false}, // wildcard is one label only
		{"other.org", []string{"example.com", "*.example.com"}, false},
		// Certificate DNS names are matched case-insensitively; the host side
		// is always lowercase already (enforced by ValidateHost).
		{"app.example.com", []string{"*.EXAMPLE.com"}, true},
	}
	for _, c := range cases {
		if got := certificateHostMatches(c.host, c.dns); got != c.want {
			t.Errorf("certificateHostMatches(%q, %v) = %v, want %v", c.host, c.dns, got, c.want)
		}
	}
}

func TestValidatePrivateKey(t *testing.T) {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	der, _ := x509.MarshalECPrivateKey(key)
	valid := string(pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: der}))
	if err := validatePrivateKey(valid); err != nil {
		t.Errorf("valid key rejected: %v", err)
	}
	if err := validatePrivateKey("garbage"); err == nil {
		t.Error("garbage must be rejected")
	}
	certificate := testCertificate(t, "example.com", nil)
	if err := validatePrivateKey(certificate); err == nil {
		t.Error("a certificate is not a private key")
	}
}
