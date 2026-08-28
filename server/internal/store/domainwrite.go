package store

import (
	"context"
	"crypto/x509"
	"encoding/pem"
	"strings"
	"time"

	"github.com/wellch4n/oops/server/internal/domain"
)

// The host policy and BizError live in domain; thin aliases keep call sites terse.
func bizErrorf(format string, args ...any) error { return domain.Bizf(format, args...) }

func NormalizeHost(host string) string { return domain.NormalizeHost(host) }

func ValidateHost(host string) error { return domain.ValidateHost(host) }

// UpsertDomainCommand mirrors the Java command object.
type UpsertDomainCommand struct {
	Host            string  `json:"host"`
	Description     *string `json:"description"`
	HTTPS           *bool   `json:"https"`
	CertMode        *string `json:"certMode"`
	CertPem         *string `json:"certPem"`
	KeyPem          *string `json:"keyPem"`
	EnvironmentName *string `json:"environmentName"`
}

type certMeta struct {
	subject  string
	notAfter time.Time
	dnsNames []string
}

func parseCertificate(certPem string) (*certMeta, error) {
	block, _ := pem.Decode([]byte(certPem))
	if block == nil || block.Type != "CERTIFICATE" {
		return nil, bizErrorf("Invalid certificate PEM")
	}
	certificate, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return nil, bizErrorf("Invalid certificate PEM")
	}
	dnsNames := certificate.DNSNames
	if len(dnsNames) == 0 && certificate.Subject.CommonName != "" {
		dnsNames = []string{certificate.Subject.CommonName}
	}
	return &certMeta{
		subject:  certificate.Subject.String(),
		notAfter: certificate.NotAfter.UTC(),
		dnsNames: dnsNames,
	}, nil
}

func validatePrivateKey(keyPem string) error {
	block, _ := pem.Decode([]byte(keyPem))
	if block == nil || !strings.Contains(block.Type, "PRIVATE KEY") {
		return bizErrorf("Invalid private key PEM")
	}
	return nil
}

func certificateHostMatches(host string, dnsNames []string) bool {
	for _, dnsName := range dnsNames {
		lowered := strings.ToLower(dnsName)
		if lowered == host {
			return true
		}
		if wildcard, isWildcard := strings.CutPrefix(lowered, "*."); isWildcard {
			if host == wildcard || (strings.HasSuffix(host, "."+wildcard) &&
				!strings.Contains(strings.TrimSuffix(host, "."+wildcard), ".")) {
				return true
			}
		}
	}
	return false
}

func (s *Store) requireDomainEnvironment(ctx context.Context, environmentName *string) (string, error) {
	if environmentName == nil || strings.TrimSpace(*environmentName) == "" {
		return "", bizErrorf("Domain environment is required")
	}
	trimmed := strings.TrimSpace(*environmentName)
	if !s.environmentExists(ctx, trimmed) {
		return "", bizErrorf("Environment not found: %s", trimmed)
	}
	return trimmed, nil
}

// applyCertFields mirrors DomainService.applyCertFields.
func (s *Store) applyCertFields(record *domainRecord, request UpsertDomainCommand) error {
	https := request.HTTPS != nil && *request.HTTPS
	record.HTTPS = &https
	if !https {
		record.CertMode, record.CertPem, record.KeyPem = nil, nil, nil
		record.CertSubject, record.CertNotAfter = nil, nil
		return nil
	}
	if request.CertMode == nil || *request.CertMode == "" {
		return bizErrorf("Certificate mode is required when HTTPS is enabled")
	}
	record.CertMode = request.CertMode
	if *request.CertMode == "AUTO" {
		record.CertPem, record.KeyPem = nil, nil
		record.CertSubject, record.CertNotAfter = nil, nil
		return nil
	}
	// UPLOADED
	hasNewCert := request.CertPem != nil && strings.TrimSpace(*request.CertPem) != ""
	hasNewKey := request.KeyPem != nil && strings.TrimSpace(*request.KeyPem) != ""
	if hasNewCert != hasNewKey {
		return bizErrorf("Certificate and private key must be provided together")
	}
	if hasNewCert {
		host := ""
		if record.Host != nil {
			host = *record.Host
		}
		meta, err := parseCertificate(*request.CertPem)
		if err != nil {
			return err
		}
		if err := validatePrivateKey(*request.KeyPem); err != nil {
			return err
		}
		if !certificateHostMatches(host, meta.dnsNames) {
			return bizErrorf("Certificate does not match domain, certificate is for: %s",
				strings.Join(meta.dnsNames, ", "))
		}
		record.CertPem = request.CertPem
		record.KeyPem = request.KeyPem
		subject := meta.subject
		record.CertSubject = &subject
		record.CertNotAfter = &LocalDateTime{Time: meta.notAfter}
		return nil
	}
	if record.CertPem == nil || strings.TrimSpace(*record.CertPem) == "" {
		return bizErrorf("UPLOADED mode requires certificate and private key")
	}
	return nil
}

func (s *Store) domainHostExists(ctx context.Context, host string) (bool, error) {
	var count int64
	err := s.orm.WithContext(ctx).Model(&domainRecord{}).
		Where("host = ?", host).Count(&count).Error
	return count > 0, err
}

func (s *Store) CreateDomain(ctx context.Context, request UpsertDomainCommand) (*DomainView, error) {
	host := NormalizeHost(request.Host)
	if err := ValidateHost(host); err != nil {
		return nil, err
	}
	if exists, err := s.domainHostExists(ctx, host); err != nil {
		return nil, err
	} else if exists {
		return nil, bizErrorf("Domain already exists: %s", host)
	}
	environmentName, err := s.requireDomainEnvironment(ctx, request.EnvironmentName)
	if err != nil {
		return nil, err
	}
	record := domainRecord{
		ID: NewNanoID(), CreatedTime: Now(),
		Host: &host, Description: request.Description, EnvironmentName: &environmentName,
	}
	if err := s.applyCertFields(&record, request); err != nil {
		return nil, err
	}
	if err := s.orm.WithContext(ctx).Create(&record).Error; err != nil {
		return nil, err
	}
	view := domainRecordToView(&record)
	return &view, nil
}

func (s *Store) FindDomain(ctx context.Context, id string) (*DomainView, error) {
	var record domainRecord
	if err := s.orm.WithContext(ctx).Where("id = ?", id).First(&record).Error; err != nil {
		return nil, bizErrorf("Domain not found: %s", id)
	}
	view := domainRecordToView(&record)
	return &view, nil
}

func (s *Store) UpdateDomain(ctx context.Context, id string, request UpsertDomainCommand) (*DomainView, error) {
	var record domainRecord
	if err := s.orm.WithContext(ctx).Where("id = ?", id).First(&record).Error; err != nil {
		return nil, bizErrorf("Domain not found: %s", id)
	}
	newHost := NormalizeHost(request.Host)
	if err := ValidateHost(newHost); err != nil {
		return nil, err
	}
	if record.Host == nil || newHost != *record.Host {
		if exists, err := s.domainHostExists(ctx, newHost); err != nil {
			return nil, err
		} else if exists {
			return nil, bizErrorf("Domain already exists: %s", newHost)
		}
	}
	environmentName, err := s.requireDomainEnvironment(ctx, request.EnvironmentName)
	if err != nil {
		return nil, err
	}
	if err := s.rejectRebindingWhileInUse(ctx, &record, newHost, environmentName); err != nil {
		return nil, err
	}
	record.Host = &newHost
	record.Description = request.Description
	record.EnvironmentName = &environmentName
	if err := s.applyCertFields(&record, request); err != nil {
		return nil, err
	}
	err = s.orm.WithContext(ctx).Model(&domainRecord{}).
		Where("id = ?", id).
		Updates(map[string]any{
			"host": record.Host, "description": record.Description,
			"https": record.HTTPS, "cert_mode": record.CertMode,
			"cert_pem": record.CertPem, "key_pem": record.KeyPem,
			"cert_subject": record.CertSubject, "cert_not_after": record.CertNotAfter,
			"environment_name": record.EnvironmentName,
		}).Error
	if err != nil {
		return nil, err
	}
	view := domainRecordToView(&record)
	return &view, nil
}

// rejectRebindingWhileInUse mirrors DomainService: a domain that currently
// governs an application host (longest-suffix match) cannot be moved away.
func (s *Store) rejectRebindingWhileInUse(ctx context.Context, record *domainRecord, newHost, newEnvironmentName string) error {
	currentHost := ""
	if record.Host != nil {
		currentHost = *record.Host
	}
	currentEnvironment := ""
	if record.EnvironmentName != nil {
		currentEnvironment = *record.EnvironmentName
	}
	if newHost == currentHost && newEnvironmentName == currentEnvironment {
		return nil
	}
	domains, err := s.ListDomains(ctx)
	if err != nil {
		return err
	}
	var serviceConfigs []serviceConfigRecord
	if err := s.orm.WithContext(ctx).Find(&serviceConfigs).Error; err != nil {
		return err
	}
	for i := range serviceConfigs {
		serviceConfig := &serviceConfigs[i]
		if !serviceConfig.EnvironmentConfigs.Valid {
			continue
		}
		for _, config := range serviceConfig.EnvironmentConfigs.Data {
			if config.Host == nil || *config.Host == "" {
				continue
			}
			host := strings.ToLower(strings.TrimSpace(*config.Host))
			var governing *DomainView
			longest := -1
			for j := range domains {
				candidateHost := domains[j].Host
				if candidateHost == nil {
					continue
				}
				if domain.HostCoveredBy(host, *candidateHost) && len(*candidateHost) > longest {
					governing = &domains[j]
					longest = len(*candidateHost)
				}
			}
			if governing == nil || governing.ID != record.ID {
				continue
			}
			stillCovered := domain.HostCoveredBy(host, newHost) &&
				config.EnvironmentName != nil && newEnvironmentName == *config.EnvironmentName
			if !stillCovered {
				environmentLabel := ""
				if config.EnvironmentName != nil {
					environmentLabel = *config.EnvironmentName
				}
				return bizErrorf("Domain is in use by application %s/%s (host %s, environment %s), remove that host first",
					serviceConfig.Namespace, serviceConfig.ApplicationName, host, environmentLabel)
			}
		}
	}
	return nil
}

func (s *Store) DeleteDomain(ctx context.Context, id string) error {
	result := s.orm.WithContext(ctx).Where("id = ?", id).Delete(&domainRecord{})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return bizErrorf("Domain not found: %s", id)
	}
	return nil
}
