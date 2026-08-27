package store

import (
	"context"
	"database/sql"
)

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
	EnvironmentName *string        `json:"environmentName"`
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
	rows, err := s.db.QueryContext(ctx,
		"SELECT id, host, cert_mode, cert_pem, key_pem FROM domain")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	domains := []DomainFull{}
	for rows.Next() {
		var domain DomainFull
		var host, certMode, certPem, keyPem sql.NullString
		if err := rows.Scan(&domain.ID, &host, &certMode, &certPem, &keyPem); err != nil {
			return nil, err
		}
		domain.Host = host.String
		if certMode.Valid {
			domain.CertMode = &certMode.String
		}
		domain.CertPem, domain.KeyPem = certPem.String, keyPem.String
		domains = append(domains, domain)
	}
	return domains, rows.Err()
}

func (s *Store) ListDomains(ctx context.Context) ([]DomainView, error) {
	// environment_name arrived in V21; migrations are Java-owned, so a dev
	// database that has not been migrated yet may lack the column. Fall back
	// to NULL rather than failing the whole listing.
	environmentColumn := "environment_name"
	if !s.columnExists(ctx, "domain", "environment_name") {
		environmentColumn = "NULL"
	}
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, host, description, https, cert_mode, cert_pem, cert_subject,
		        cert_not_after, created_time, `+environmentColumn+`
		 FROM domain ORDER BY created_time`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	domains := []DomainView{}
	for rows.Next() {
		var domain DomainView
		var certPem sql.NullString
		if err := rows.Scan(&domain.ID, &domain.Host, &domain.Description, &domain.HTTPS,
			&domain.CertMode, &certPem, &domain.CertSubject, &domain.CertNotAfter,
			&domain.CreatedTime, &domain.EnvironmentName); err != nil {
			return nil, err
		}
		domain.HasUploadedCert = certPem.Valid && certPem.String != ""
		domains = append(domains, domain)
	}
	return domains, rows.Err()
}
