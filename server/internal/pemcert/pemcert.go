// Package pemcert validates the certificate and private key uploaded for a
// managed domain, mirroring the Java PemCertificateParser message for message.
package pemcert

import (
	"crypto/ecdsa"
	"crypto/rsa"
	"crypto/x509"
	"encoding/pem"
	"strings"
	"time"

	"github.com/wellch4n/oops/server/internal/domain"
)

// CertMeta is what the UI shows for an uploaded certificate.
type CertMeta struct {
	Subject  string
	NotAfter time.Time
	DNSNames []string
}

const (
	certificateHeader = "-----BEGIN CERTIFICATE-----"
	pkcs8Header       = "-----BEGIN PRIVATE KEY-----"
	pkcs1RSAHeader    = "-----BEGIN RSA PRIVATE KEY-----"
	pkcs1ECHeader     = "-----BEGIN EC PRIVATE KEY-----"
)

// ParseCertificate parses the first CERTIFICATE block of a PEM bundle.
// Subject is rendered RFC 2253 style ("CN=example.com,O=Acme"), and DNSNames
// is the lower-cased CN followed by the SAN dNSName entries, de-duplicated.
func ParseCertificate(certificatePEM string) (*CertMeta, error) {
	if strings.TrimSpace(certificatePEM) == "" {
		return nil, domain.Biz("Certificate content is empty")
	}
	if !strings.Contains(certificatePEM, certificateHeader) {
		return nil, domain.Biz("Invalid certificate format, expected PEM (-----BEGIN CERTIFICATE-----)")
	}
	block := firstBlock(certificatePEM, "CERTIFICATE")
	if block == nil {
		return nil, domain.Biz("Failed to parse certificate: no CERTIFICATE block could be decoded")
	}
	certificate, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return nil, domain.BizWrap("Failed to parse certificate: "+err.Error(), err)
	}
	names := make([]string, 0, 1+len(certificate.DNSNames))
	seen := map[string]bool{}
	appendName := func(name string) {
		lowered := strings.ToLower(strings.TrimSpace(name))
		if lowered == "" || seen[lowered] {
			return
		}
		seen[lowered] = true
		names = append(names, lowered)
	}
	appendName(certificate.Subject.CommonName)
	for _, dnsName := range certificate.DNSNames {
		appendName(dnsName)
	}
	return &CertMeta{
		Subject:  certificate.Subject.String(),
		NotAfter: certificate.NotAfter,
		DNSNames: names,
	}, nil
}

// ValidatePrivateKey accepts only an unencrypted PKCS#8 RSA or EC key.
func ValidatePrivateKey(keyPEM string) error {
	if strings.TrimSpace(keyPEM) == "" {
		return domain.Biz("Private key content is empty")
	}
	if strings.Contains(keyPEM, pkcs1RSAHeader) || strings.Contains(keyPEM, pkcs1ECHeader) {
		return domain.Biz("PKCS#1 format is not supported, please convert to PKCS#8 using: openssl pkcs8 -topk8 -nocrypt (-----BEGIN PRIVATE KEY-----)")
	}
	if !strings.Contains(keyPEM, pkcs8Header) {
		return domain.Biz("Invalid private key format, expected PKCS#8 PEM (-----BEGIN PRIVATE KEY-----)")
	}
	block := firstBlock(keyPEM, "PRIVATE KEY")
	if block == nil {
		return domain.Biz("Failed to parse private key (neither RSA nor EC)")
	}
	key, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return domain.BizWrap("Failed to parse private key (neither RSA nor EC)", err)
	}
	switch key.(type) {
	case *rsa.PrivateKey, *ecdsa.PrivateKey:
		return nil
	default:
		return domain.Biz("Failed to parse private key (neither RSA nor EC)")
	}
}

// HostMatches reports whether host equals any of names once both sides have
// their leading "*." stripped and are lower-cased. A wildcard certificate
// "*.example.com" therefore matches the domain "example.com" and vice versa,
// but neither matches "app.example.com".
func HostMatches(host string, names []string) bool {
	normalizedHost := normalizeHost(host)
	if normalizedHost == "" {
		return false
	}
	for _, name := range names {
		if normalizeHost(name) == normalizedHost {
			return true
		}
	}
	return false
}

func normalizeHost(value string) string {
	trimmed := strings.ToLower(strings.TrimSpace(value))
	return strings.TrimPrefix(trimmed, "*.")
}

func firstBlock(content, blockType string) *pem.Block {
	rest := []byte(content)
	for {
		block, remaining := pem.Decode(rest)
		if block == nil {
			return nil
		}
		if block.Type == blockType {
			return block
		}
		rest = remaining
	}
}
