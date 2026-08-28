package store

import (
	"context"
)

// domainRecord is the GORM model of the domain table.
type domainRecord struct {
	ID              string
	CreatedTime     *LocalDateTime
	Host            *string
	Description     *string
	HTTPS           *bool `gorm:"column:https"`
	CertMode        *string
	CertPem         *string
	KeyPem          *string
	CertSubject     *string
	CertNotAfter    *LocalDateTime
	EnvironmentName *string
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
	EnvironmentName *string        `json:"environmentName"`
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
		EnvironmentName: record.EnvironmentName,
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
