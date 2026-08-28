package store

import (
	"context"

	"github.com/wellch4n/oops/server/internal/domain"
)

type Namespace struct {
	ID          string         `json:"id"`
	CreatedTime *LocalDateTime `json:"createdTime"`
	Name        string         `json:"name"`
	Description *string        `json:"description"`
}

func (Namespace) TableName() string { return "namespace" }

func (s *Store) ListNamespaces(ctx context.Context) ([]Namespace, error) {
	namespaces := []Namespace{}
	err := s.orm.WithContext(ctx).Order("created_time").Find(&namespaces).Error
	return namespaces, err
}

func (s *Store) CreateNamespace(ctx context.Context, name, description string) error {
	namespace := Namespace{
		ID: domain.NewID(), CreatedTime: Now(),
		Name: name, Description: &description,
	}
	return s.orm.WithContext(ctx).Create(&namespace).Error
}

func (s *Store) UpdateNamespaceDescription(ctx context.Context, name, description string) error {
	return s.orm.WithContext(ctx).Model(&Namespace{}).
		Where("name = ?", name).
		Update("description", description).Error
}

// NamespaceRecordExists checks the namespace registry (not the cluster).
func (s *Store) NamespaceRecordExists(ctx context.Context, name string) (bool, error) {
	var count int64
	err := s.orm.WithContext(ctx).Model(&Namespace{}).
		Where("name = ?", name).Count(&count).Error
	return count > 0, err
}
