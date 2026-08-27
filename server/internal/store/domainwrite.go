package store

import (
	"context"
	"crypto/x509"
	"database/sql"
	"encoding/pem"
	"errors"

	"github.com/wellch4n/oops/server/internal/domain"
	"strings"
	"time"
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

type domainRecord struct {
	ID              string
	Host            string
	Description     *string
	HTTPS           bool
	CertMode        *string
	CertPem         *string
	KeyPem          *string
	CertSubject     *string
	CertNotAfter    *LocalDateTime
	EnvironmentName *string
}

func (s *Store) environmentExists(ctx context.Context, name string) bool {
	var count int
	err := s.db.QueryRowContext(ctx,
		"SELECT COUNT(*) FROM environment WHERE name = ?", name).Scan(&count)
	return err == nil && count > 0
}

func (record *domainRecord) applyCertFields(request UpsertDomainCommand) error {
	record.HTTPS = request.HTTPS != nil && *request.HTTPS
	if !record.HTTPS {
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
		meta, err := parseCertificate(*request.CertPem)
		if err != nil {
			return err
		}
		if err := validatePrivateKey(*request.KeyPem); err != nil {
			return err
		}
		if !certificateHostMatches(record.Host, meta.dnsNames) {
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

func (record *domainRecord) toView() DomainView {
	view := DomainView{
		ID:              record.ID,
		Host:            &record.Host,
		Description:     record.Description,
		HTTPS:           &record.HTTPS,
		CertMode:        record.CertMode,
		HasUploadedCert: record.CertPem != nil && *record.CertPem != "",
		CertSubject:     record.CertSubject,
		CertNotAfter:    record.CertNotAfter,
		EnvironmentName: record.EnvironmentName,
	}
	return view
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

func (s *Store) CreateDomain(ctx context.Context, request UpsertDomainCommand) (*DomainView, error) {
	host := NormalizeHost(request.Host)
	if err := ValidateHost(host); err != nil {
		return nil, err
	}
	var exists int
	if err := s.db.QueryRowContext(ctx,
		"SELECT COUNT(*) FROM domain WHERE host = ?", host).Scan(&exists); err != nil {
		return nil, err
	}
	if exists > 0 {
		return nil, bizErrorf("Domain already exists: %s", host)
	}
	environmentName, err := s.requireDomainEnvironment(ctx, request.EnvironmentName)
	if err != nil {
		return nil, err
	}
	record := domainRecord{ID: NewNanoID(), Host: host, Description: request.Description, EnvironmentName: &environmentName}
	if err := record.applyCertFields(request); err != nil {
		return nil, err
	}
	createdTime := Now()
	_, err = s.db.ExecContext(ctx,
		`INSERT INTO domain (id, created_time, host, description, https, cert_mode, cert_pem, key_pem,
		                     cert_subject, cert_not_after, environment_name)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		record.ID, createdTime, record.Host, record.Description, record.HTTPS, record.CertMode,
		record.CertPem, record.KeyPem, record.CertSubject, record.CertNotAfter, record.EnvironmentName)
	if err != nil {
		return nil, err
	}
	view := record.toView()
	view.CreatedTime = createdTime
	return &view, nil
}

func (s *Store) findDomainRecord(ctx context.Context, id string) (*domainRecord, *LocalDateTime, error) {
	var record domainRecord
	var createdTime *LocalDateTime
	var https sql.NullBool
	err := s.db.QueryRowContext(ctx,
		`SELECT id, created_time, host, description, https, cert_mode, cert_pem, key_pem,
		        cert_subject, cert_not_after, environment_name
		 FROM domain WHERE id = ?`, id).
		Scan(&record.ID, &createdTime, &record.Host, &record.Description, &https, &record.CertMode,
			&record.CertPem, &record.KeyPem, &record.CertSubject, &record.CertNotAfter, &record.EnvironmentName)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil, bizErrorf("Domain not found: %s", id)
	}
	if err != nil {
		return nil, nil, err
	}
	record.HTTPS = https.Valid && https.Bool
	return &record, createdTime, nil
}

func (s *Store) UpdateDomain(ctx context.Context, id string, request UpsertDomainCommand) (*DomainView, error) {
	record, createdTime, err := s.findDomainRecord(ctx, id)
	if err != nil {
		return nil, err
	}
	newHost := NormalizeHost(request.Host)
	if err := ValidateHost(newHost); err != nil {
		return nil, err
	}
	if newHost != record.Host {
		var exists int
		if err := s.db.QueryRowContext(ctx,
			"SELECT COUNT(*) FROM domain WHERE host = ?", newHost).Scan(&exists); err != nil {
			return nil, err
		}
		if exists > 0 {
			return nil, bizErrorf("Domain already exists: %s", newHost)
		}
	}
	environmentName, err := s.requireDomainEnvironment(ctx, request.EnvironmentName)
	if err != nil {
		return nil, err
	}
	if err := s.rejectRebindingWhileInUse(ctx, record, newHost, environmentName); err != nil {
		return nil, err
	}
	record.Host = newHost
	record.Description = request.Description
	record.EnvironmentName = &environmentName
	if err := record.applyCertFields(request); err != nil {
		return nil, err
	}
	_, err = s.db.ExecContext(ctx,
		`UPDATE domain SET host = ?, description = ?, https = ?, cert_mode = ?, cert_pem = ?,
		        key_pem = ?, cert_subject = ?, cert_not_after = ?, environment_name = ?
		 WHERE id = ?`,
		record.Host, record.Description, record.HTTPS, record.CertMode, record.CertPem,
		record.KeyPem, record.CertSubject, record.CertNotAfter, record.EnvironmentName, id)
	if err != nil {
		return nil, err
	}
	view := record.toView()
	view.CreatedTime = createdTime
	return &view, nil
}

// rejectRebindingWhileInUse mirrors DomainService: a domain that currently
// governs an application host (longest-suffix match) cannot be moved away.
func (s *Store) rejectRebindingWhileInUse(ctx context.Context, domain *domainRecord, newHost, newEnvironmentName string) error {
	hostUnchanged := newHost == domain.Host
	environmentUnchanged := domain.EnvironmentName != nil && newEnvironmentName == *domain.EnvironmentName
	if hostUnchanged && environmentUnchanged {
		return nil
	}
	domains, err := s.ListDomains(ctx)
	if err != nil {
		return err
	}
	rows, err := s.db.QueryContext(ctx,
		"SELECT namespace, application_name, environment_configs FROM application_service_config")
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var namespace, applicationName string
		var blob sql.NullString
		if err := rows.Scan(&namespace, &applicationName, &blob); err != nil {
			return err
		}
		var configs []serviceEnvironmentConfigRow
		if err := decodeJSONColumn(blob, &configs); err != nil {
			continue
		}
		for _, config := range configs {
			if config.Host == nil || *config.Host == "" {
				continue
			}
			host := strings.ToLower(strings.TrimSpace(*config.Host))
			var governing *DomainView
			longest := -1
			for i := range domains {
				candidateHost := domains[i].Host
				if candidateHost == nil {
					continue
				}
				if (host == *candidateHost || strings.HasSuffix(host, "."+*candidateHost)) && len(*candidateHost) > longest {
					governing = &domains[i]
					longest = len(*candidateHost)
				}
			}
			if governing == nil || governing.ID != domain.ID {
				continue
			}
			stillCovered := (host == newHost || strings.HasSuffix(host, "."+newHost)) &&
				config.EnvironmentName != nil && newEnvironmentName == *config.EnvironmentName
			if !stillCovered {
				environmentLabel := ""
				if config.EnvironmentName != nil {
					environmentLabel = *config.EnvironmentName
				}
				return bizErrorf("Domain is in use by application %s/%s (host %s, environment %s), remove that host first",
					namespace, applicationName, host, environmentLabel)
			}
		}
	}
	return rows.Err()
}

func (s *Store) DeleteDomain(ctx context.Context, id string) error {
	result, err := s.db.ExecContext(ctx, "DELETE FROM domain WHERE id = ?", id)
	if err != nil {
		return err
	}
	if affected, _ := result.RowsAffected(); affected == 0 {
		return bizErrorf("Domain not found: %s", id)
	}
	return nil
}

func (s *Store) FindDomain(ctx context.Context, id string) (*DomainView, error) {
	record, createdTime, err := s.findDomainRecord(ctx, id)
	if err != nil {
		return nil, err
	}
	view := record.toView()
	view.CreatedTime = createdTime
	return &view, nil
}
