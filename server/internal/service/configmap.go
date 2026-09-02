package service

import (
	"context"

	"github.com/wellch4n/oops/server/internal/k8s"
)

// ConfigMapService reads and rewrites an application's configuration. The items
// live in Kubernetes, not in the database: a ConfigMap for plain values, a
// Secret for secret ones, and a second pair for the items mounted as files.
type ConfigMapService struct {
	services *Services
}

// List returns every config item of an application, both plain and secret.
func (s *ConfigMapService) List(ctx context.Context, environmentName, namespace, applicationName string) ([]k8s.ConfigMapItem, error) {
	environment, err := s.services.environmentByName(ctx, environmentName)
	if err != nil {
		return nil, err
	}
	return s.services.Configs.GetConfigMaps(ctx, environment, namespace, applicationName)
}

// Update rewrites the whole configuration: an item missing from the request is
// deleted, which is what makes the editor's "remove a key" work at all.
func (s *ConfigMapService) Update(ctx context.Context, environmentName, namespace, applicationName string, commands []k8s.ConfigMapCommand) error {
	environment, err := s.services.environmentByName(ctx, environmentName)
	if err != nil {
		return err
	}
	return s.services.Configs.UpdateConfigMaps(ctx, environment, namespace, applicationName, commands)
}
