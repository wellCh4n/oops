package service

import (
	"context"
	"strings"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/store"
)

// NamespaceService manages the OOPS namespace records. These are the product's
// own grouping, not Kubernetes namespaces — creating one here does not create
// one in a cluster (that is POST /api/kubernetes/namespaces).
type NamespaceService struct {
	store *store.Store
}

func (s *NamespaceService) List(ctx context.Context) ([]domain.Namespace, error) {
	return s.store.Namespaces().FindAll(ctx)
}

// Create adds a namespace. A repeat of an existing name is left alone rather
// than rejected, so a retried request is harmless.
func (s *NamespaceService) Create(ctx context.Context, name, description string) error {
	name = strings.TrimSpace(name)
	if err := domain.CheckResourceName(name); err != nil {
		return err
	}
	existing, err := s.store.Namespaces().FindByName(ctx, name)
	if err != nil {
		return err
	}
	if existing != nil {
		return nil
	}
	_, err = s.store.Namespaces().Save(ctx, &domain.Namespace{
		Name:        &name,
		Description: domain.StringOrNil(description),
	})
	return err
}

// Update changes a namespace's description, keyed by name.
func (s *NamespaceService) Update(ctx context.Context, name, description string) error {
	existing, err := s.store.Namespaces().FindByName(ctx, strings.TrimSpace(name))
	if err != nil {
		return err
	}
	if existing == nil {
		return domain.Bizf("Namespace not found: %s", name)
	}
	existing.Description = domain.StringOrNil(description)
	_, err = s.store.Namespaces().Save(ctx, existing)
	return err
}
