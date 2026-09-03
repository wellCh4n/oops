package store

import (
	"context"

	"github.com/wellch4n/oops/server/internal/domain"
)

// DomainRepository owns the managed-domain table and its certificates.
type DomainRepository struct {
	store *Store
}

func domainFromRow(row domainRow) domain.Domain {
	return domain.Domain{
		ID:           row.ID,
		CreatedTime:  row.CreatedTime,
		Host:         ptrOf(row.Host),
		Description:  ptrOf(row.Description),
		HTTPS:        boolPtrOf(row.HTTPS),
		CertMode:     enumPtrOf[domain.DomainCertMode](row.CertMode),
		CertPem:      row.CertPem.Ptr(),
		KeyPem:       row.KeyPem.Ptr(),
		CertSubject:  ptrOf(row.CertSubject),
		CertNotAfter: row.CertNotAfter,
		Environment:  ptrOf(row.Environment),
	}
}

// FindAll returns every managed domain.
func (r *DomainRepository) FindAll(ctx context.Context) ([]domain.Domain, error) {
	rows, err := list[domainRow](ctx, r.store.db, `SELECT * FROM domain`)
	if err != nil {
		return nil, err
	}
	result := make([]domain.Domain, 0, len(rows))
	for _, row := range rows {
		result = append(result, domainFromRow(row))
	}
	return result, nil
}

// FindByID loads a domain by primary key; nil when absent.
func (r *DomainRepository) FindByID(ctx context.Context, id string) (*domain.Domain, error) {
	row, err := getOrNil[domainRow](ctx, r.store.db, `SELECT * FROM domain WHERE id = ? LIMIT 1`, id)
	if err != nil || row == nil {
		return nil, err
	}
	result := domainFromRow(*row)
	return &result, nil
}

// ExistsByID reports whether a domain row exists.
func (r *DomainRepository) ExistsByID(ctx context.Context, id string) (bool, error) {
	return exists(ctx, r.store.db, `SELECT 1 FROM domain WHERE id = ? LIMIT 1`, id)
}

// ExistsByHost guards against registering the same host twice.
func (r *DomainRepository) ExistsByHost(ctx context.Context, host string) (bool, error) {
	return exists(ctx, r.store.db, `SELECT 1 FROM domain WHERE host = ? LIMIT 1`, host)
}

// Save inserts or updates a domain. A duplicate host surfaces as ErrDuplicate.
func (r *DomainRepository) Save(ctx context.Context, record *domain.Domain) (*domain.Domain, error) {
	found := false
	var err error
	if record.ID != "" {
		if found, err = exists(ctx, r.store.db, `SELECT 1 FROM domain WHERE id = ? LIMIT 1`, record.ID); err != nil {
			return nil, err
		}
	}
	if found {
		_, err = execRows(ctx, r.store.db,
			`UPDATE domain
SET created_time = ?, cert_mode = ?, cert_not_after = ?, cert_pem = ?, cert_subject = ?,
    description = ?, host = ?, https = ?, key_pem = ?, environment = ?
WHERE id = ?`,
			record.CreatedTime, enumString(record.CertMode), record.CertNotAfter, EncryptedOf(record.CertPem),
			nullString(record.CertSubject), nullString(record.Description), nullString(record.Host),
			nullBool(record.HTTPS), EncryptedOf(record.KeyPem), nullString(record.Environment), record.ID)
	} else {
		record.ID = ensureID(record.ID)
		if record.CreatedTime.IsZero() {
			record.CreatedTime = domain.Now()
		}
		err = exec(ctx, r.store.db,
			`INSERT INTO domain
(id, created_time, cert_mode, cert_not_after, cert_pem, cert_subject, description, host, https, key_pem, environment)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
			record.ID, record.CreatedTime, enumString(record.CertMode), record.CertNotAfter,
			EncryptedOf(record.CertPem), nullString(record.CertSubject), nullString(record.Description),
			nullString(record.Host), nullBool(record.HTTPS), EncryptedOf(record.KeyPem), nullString(record.Environment))
	}
	if err != nil {
		return nil, err
	}
	return record, nil
}

// DeleteByID removes a domain.
func (r *DomainRepository) DeleteByID(ctx context.Context, id string) error {
	_, err := execRows(ctx, r.store.db, `DELETE FROM domain WHERE id = ?`, id)
	return err
}
