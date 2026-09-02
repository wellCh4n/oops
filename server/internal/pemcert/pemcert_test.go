package pemcert

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"reflect"
	"testing"
	"time"

	"github.com/wellch4n/oops/server/internal/domain"
)

func selfSigned(t *testing.T, commonName string, dnsNames []string) (certPEM string, key *ecdsa.PrivateKey) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: commonName, Organization: []string{"Acme"}},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(24 * time.Hour),
		DNSNames:     dnsNames,
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})), key
}

func TestParseCertificate(t *testing.T) {
	certPEM, _ := selfSigned(t, "Example.com", []string{"example.com", "*.Example.com", "www.example.com"})
	meta, err := ParseCertificate(certPEM)
	if err != nil {
		t.Fatal(err)
	}
	if meta.Subject != "CN=Example.com,O=Acme" {
		t.Fatalf("subject %q", meta.Subject)
	}
	wantNames := []string{"example.com", "*.example.com", "www.example.com"}
	if !reflect.DeepEqual(meta.DNSNames, wantNames) {
		t.Fatalf("dns names %v", meta.DNSNames)
	}
	if time.Until(meta.NotAfter) < 23*time.Hour {
		t.Fatalf("not after %v", meta.NotAfter)
	}
}

func TestParseCertificateErrors(t *testing.T) {
	cases := map[string]string{
		"":        "Certificate content is empty",
		"garbage": "Invalid certificate format, expected PEM (-----BEGIN CERTIFICATE-----)",
		"-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n": "Failed to parse certificate: ",
	}
	for input, prefix := range cases {
		_, err := ParseCertificate(input)
		if err == nil || !domain.IsBiz(err) {
			t.Fatalf("%q: expected biz error, got %v", input, err)
		}
		if got := domain.BizMessage(err); len(got) < len(prefix) || got[:len(prefix)] != prefix {
			t.Fatalf("%q: got %q want prefix %q", input, got, prefix)
		}
	}
}

func TestValidatePrivateKey(t *testing.T) {
	_, ecKey := selfSigned(t, "example.com", nil)
	ecDER, err := x509.MarshalPKCS8PrivateKey(ecKey)
	if err != nil {
		t.Fatal(err)
	}
	if err := ValidatePrivateKey(string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: ecDER}))); err != nil {
		t.Fatalf("EC PKCS#8: %v", err)
	}
	rsaKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	rsaDER, err := x509.MarshalPKCS8PrivateKey(rsaKey)
	if err != nil {
		t.Fatal(err)
	}
	if err := ValidatePrivateKey(string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: rsaDER}))); err != nil {
		t.Fatalf("RSA PKCS#8: %v", err)
	}
	pkcs1 := string(pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(rsaKey)}))
	if got := domain.BizMessage(ValidatePrivateKey(pkcs1)); got != "PKCS#1 format is not supported, please convert to PKCS#8 using: openssl pkcs8 -topk8 -nocrypt (-----BEGIN PRIVATE KEY-----)" {
		t.Fatalf("pkcs1: %q", got)
	}
	if got := domain.BizMessage(ValidatePrivateKey(" ")); got != "Private key content is empty" {
		t.Fatalf("empty: %q", got)
	}
	if got := domain.BizMessage(ValidatePrivateKey("nonsense")); got != "Invalid private key format, expected PKCS#8 PEM (-----BEGIN PRIVATE KEY-----)" {
		t.Fatalf("format: %q", got)
	}
	broken := "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n"
	if got := domain.BizMessage(ValidatePrivateKey(broken)); got != "Failed to parse private key (neither RSA nor EC)" {
		t.Fatalf("broken: %q", got)
	}
}

func TestHostMatches(t *testing.T) {
	names := []string{"*.example.com"}
	if !HostMatches("example.com", names) {
		t.Fatal("wildcard cert should match the bare domain")
	}
	if !HostMatches("*.EXAMPLE.com", []string{"example.com"}) {
		t.Fatal("wildcard domain should match a bare cert name")
	}
	if HostMatches("app.example.com", names) {
		t.Fatal("wildcard must not match a subdomain (exact equality after stripping)")
	}
	if HostMatches("", names) || HostMatches("example.com", nil) {
		t.Fatal("empty inputs never match")
	}
}
