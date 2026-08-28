// Managed domains: host matching, certificate validation, and admin writes.
package store

import (
	"context"
	"crypto/x509"
	"encoding/pem"
	"github.com/wellch4n/oops/server/internal/domain"
	"strings"
	"time"
)

// domainRecord is the GORM model of the domain table.
type domainRecord struct {
	ID           string
	CreatedTime  *LocalDateTime
	Host         *string
	Description  *string
	HTTPS        *bool `gorm:"column:https"`
	CertMode     *string
	CertPem      *string
	KeyPem       *string
	CertSubject  *string
	CertNotAfter *LocalDateTime
	Environment  *string
}

func (domainRecord) TableName() string { return "domain" }

// DomainView mirrors DomainDto: cert/key PEM bodies never leave the backend,
// only the hasUploadedCert marker does.
type DomainView struct {
	ID              string         `json:"id"`
	Host            *string        `json:"host"`
	Description     *string        `json:"description"`
	HTTPS           *bool          `json:"https"`
	CertMode        *string        `json:"certMode"`
	HasUploadedCert bool           `json:"hasUploadedCert"`
	CertSubject     *string        `json:"certSubject"`
	CertNotAfter    *LocalDateTime `json:"certNotAfter"`
	CreatedTime     *LocalDateTime `json:"createdTime"`
	Environment     *string        `json:"environment"`
}

func domainRecordToView(record *domainRecord) DomainView {
	return DomainView{
		ID:              record.ID,
		Host:            record.Host,
		Description:     record.Description,
		HTTPS:           record.HTTPS,
		CertMode:        record.CertMode,
		HasUploadedCert: record.CertPem != nil && *record.CertPem != "",
		CertSubject:     record.CertSubject,
		CertNotAfter:    record.CertNotAfter,
		CreatedTime:     record.CreatedTime,
		Environment:     record.Environment,
	}
}

func (s *Store) ListDomains(ctx context.Context) ([]DomainView, error) {
	var records []domainRecord
	if err := s.orm.WithContext(ctx).Order("created_time").Find(&records).Error; err != nil {
		return nil, err
	}
	views := []DomainView{}
	for i := range records {
		views = append(views, domainRecordToView(&records[i]))
	}
	return views, nil
}

// DomainFull carries the PEM bodies for the deploy engine's TLS secret sync.
type DomainFull struct {
	ID       string
	Host     string
	CertMode *string
	CertPem  string
	KeyPem   string
}

func (s *Store) ListDomainsFull(ctx context.Context) ([]DomainFull, error) {
	var records []domainRecord
	if err := s.orm.WithContext(ctx).Find(&records).Error; err != nil {
		return nil, err
	}
	domains := make([]DomainFull, 0, len(records))
	for _, record := range records {
		full := DomainFull{ID: record.ID, CertMode: record.CertMode}
		if record.Host != nil {
			full.Host = *record.Host
		}
		if record.CertPem != nil {
			full.CertPem = *record.CertPem
		}
		if record.KeyPem != nil {
			full.KeyPem = *record.KeyPem
		}
		domains = append(domains, full)
	}
	return domains, nil
}

// UpsertDomainRequest mirrors the Java command object.
type UpsertDomainRequest struct {
	Host        string  `json:"host"`
	Description *string `json:"description"`
	HTTPS       *bool   `json:"https"`
	CertMode    *string `json:"certMode"`
	CertPem     *string `json:"certPem"`
	KeyPem      *string `json:"keyPem"`
	Environment *string `json:"environment"`
}

type certMeta struct {
	subject  string
	notAfter time.Time
	dnsNames []string
}

func parseCertificate(certPem string) (*certMeta, error) {
	block, _ := pem.Decode([]byte(certPem))
	if block == nil || block.Type != "CERTIFICATE" {
		return nil, domain.Bizf("Invalid certificate PEM")
	}
	certificate, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return nil, domain.Bizf("Invalid certificate PEM")
	}
	dnsNames := certificate.DNSNames
	if len(dnsNames) == 0 && certificate.Subject.CommonName != "" {
		dnsNames = []string{certificate.Subject.CommonName}
	}
	return &certMeta{
		subject:  certificate.Subject.String(),
		notAfter: certificate.NotAfter.Local(),
		dnsNames: dnsNames,
	}, nil
}

func validatePrivateKey(keyPem string) error {
	block, _ := pem.Decode([]byte(keyPem))
	if block == nil || !strings.Contains(block.Type, "PRIVATE KEY") {
		return domain.Bizf("Invalid private key PEM")
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
		return "", domain.Bizf("Domain environment is required")
	}
	trimmed := strings.TrimSpace(*environmentName)
	if !s.environmentExists(ctx, trimmed) {
		return "", domain.Bizf("Environment not found: %s", trimmed)
	}
	return trimmed, nil
}

// applyCertFields mirrors DomainService.applyCertFields.
func (s *Store) applyCertFields(record *domainRecord, request UpsertDomainRequest) error {
	https := request.HTTPS != nil && *request.HTTPS
	record.HTTPS = &https
	if !https {
		record.CertMode, record.CertPem, record.KeyPem = nil, nil, nil
		record.CertSubject, record.CertNotAfter = nil, nil
		return nil
	}
	if request.CertMode == nil || *request.CertMode == "" {
		return domain.Bizf("Certificate mode is required when HTTPS is enabled")
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
		return domain.Bizf("Certificate and private key must be provided together")
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
			return domain.Bizf("Certificate does not match domain, certificate is for: %s",
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
		return domain.Bizf("UPLOADED mode requires certificate and private key")
	}
	return nil
}

func (s *Store) domainHostExists(ctx context.Context, host string) (bool, error) {
	var count int64
	err := s.orm.WithContext(ctx).Model(&domainRecord{}).
		Where("host = ?", host).Count(&count).Error
	return count > 0, err
}

func (s *Store) CreateDomain(ctx context.Context, request UpsertDomainRequest) (*DomainView, error) {
	host := domain.NormalizeHost(request.Host)
	if err := domain.ValidateHost(host); err != nil {
		return nil, err
	}
	if exists, err := s.domainHostExists(ctx, host); err != nil {
		return nil, err
	} else if exists {
		return nil, domain.Bizf("Domain already exists: %s", host)
	}
	environmentName, err := s.requireDomainEnvironment(ctx, request.Environment)
	if err != nil {
		return nil, err
	}
	record := domainRecord{
		ID: domain.NewID(), CreatedTime: Now(),
		Host: &host, Description: request.Description, Environment: &environmentName,
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
		return nil, domain.Bizf("Domain not found: %s", id)
	}
	view := domainRecordToView(&record)
	return &view, nil
}

func (s *Store) UpdateDomain(ctx context.Context, id string, request UpsertDomainRequest) (*DomainView, error) {
	var record domainRecord
	if err := s.orm.WithContext(ctx).Where("id = ?", id).First(&record).Error; err != nil {
		return nil, domain.Bizf("Domain not found: %s", id)
	}
	newHost := domain.NormalizeHost(request.Host)
	if err := domain.ValidateHost(newHost); err != nil {
		return nil, err
	}
	if record.Host == nil || newHost != *record.Host {
		if exists, err := s.domainHostExists(ctx, newHost); err != nil {
			return nil, err
		} else if exists {
			return nil, domain.Bizf("Domain already exists: %s", newHost)
		}
	}
	environmentName, err := s.requireDomainEnvironment(ctx, request.Environment)
	if err != nil {
		return nil, err
	}
	if err := s.rejectRebindingWhileInUse(ctx, &record, newHost, environmentName); err != nil {
		return nil, err
	}
	record.Host = &newHost
	record.Description = request.Description
	record.Environment = &environmentName
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
			"environment": record.Environment,
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
	if record.Environment != nil {
		currentEnvironment = *record.Environment
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
				config.Environment != nil && newEnvironmentName == *config.Environment
			if !stillCovered {
				environmentLabel := ""
				if config.Environment != nil {
					environmentLabel = *config.Environment
				}
				return domain.Bizf("Domain is in use by application %s/%s (host %s, environment %s), remove that host first",
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
		return domain.Bizf("Domain not found: %s", id)
	}
	return nil
}
