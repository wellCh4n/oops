package store

import (
	"context"

	"github.com/wellch4n/oops/server/internal/domain"
)

// NamespaceRepository owns the namespace table — this product's own grouping,
// not a Kubernetes namespace.
type NamespaceRepository struct {
	store *Store
}

func namespaceFromRow(row namespaceRow) domain.Namespace {
	return domain.Namespace{
		ID:          row.ID,
		CreatedTime: row.CreatedTime,
		Name:        orNil(row.Name),
		Description: orNil(row.Description),
	}
}

// FindAll returns every namespace.
func (r *NamespaceRepository) FindAll(ctx context.Context) ([]domain.Namespace, error) {
	rows, err := list[namespaceRow](ctx, r.store.db, `SELECT * FROM namespace`)
	if err != nil {
		return nil, err
	}
	result := make([]domain.Namespace, 0, len(rows))
	for _, row := range rows {
		result = append(result, namespaceFromRow(row))
	}
	return result, nil
}

// FindByName loads a namespace by name; nil when absent.
func (r *NamespaceRepository) FindByName(ctx context.Context, name string) (*domain.Namespace, error) {
	row, err := getOrNil[namespaceRow](ctx, r.store.db, `SELECT * FROM namespace WHERE name = ? LIMIT 1`, name)
	if err != nil || row == nil {
		return nil, err
	}
	result := namespaceFromRow(*row)
	return &result, nil
}

// Save inserts or updates a namespace.
func (r *NamespaceRepository) Save(ctx context.Context, record *domain.Namespace) (*domain.Namespace, error) {
	found := false
	var err error
	if record.ID != "" {
		if found, err = exists(ctx, r.store.db, `SELECT 1 FROM namespace WHERE id = ? LIMIT 1`, record.ID); err != nil {
			return nil, err
		}
	}
	if found {
		_, err = execRows(ctx, r.store.db,
			`UPDATE namespace SET created_time = ?, description = ?, name = ? WHERE id = ?`,
			record.CreatedTime, domain.Deref(record.Description), domain.Deref(record.Name), record.ID)
	} else {
		record.ID = ensureID(record.ID)
		if record.CreatedTime.IsZero() {
			record.CreatedTime = domain.Now()
		}
		err = exec(ctx, r.store.db,
			`INSERT INTO namespace (id, created_time, description, name) VALUES (?, ?, ?, ?)`,
			record.ID, record.CreatedTime, domain.Deref(record.Description), domain.Deref(record.Name))
	}
	if err != nil {
		return nil, err
	}
	return record, nil
}
