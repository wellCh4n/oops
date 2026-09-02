package service

import (
	"context"

	"github.com/wellch4n/oops/server/internal/k8s"
)

// ClusterService is the read-only cluster inspection the UI offers: nodes and
// the ServiceAccounts an application may run as.
type ClusterService struct {
	services *Services
}

// ListNodes returns the node list for one environment.
func (s *ClusterService) ListNodes(ctx context.Context, environmentName string) ([]k8s.NodeStatusView, error) {
	environment, err := s.services.environmentByName(ctx, environmentName)
	if err != nil {
		return nil, err
	}
	return k8s.ListNodes(ctx, s.services.Pool, environment)
}

// SetNodeSchedulable cordons or uncordons a node.
func (s *ClusterService) SetNodeSchedulable(ctx context.Context, environmentName, nodeName string, schedulable bool) error {
	environment, err := s.services.environmentByName(ctx, environmentName)
	if err != nil {
		return err
	}
	return k8s.SetNodeSchedulable(ctx, s.services.Pool, environment, nodeName, schedulable)
}

// ListServiceAccounts backs the expert config's service account picker.
func (s *ClusterService) ListServiceAccounts(ctx context.Context, environmentName, namespace string) ([]string, error) {
	environment, err := s.services.environmentByName(ctx, environmentName)
	if err != nil {
		return nil, err
	}
	return k8s.ListServiceAccounts(ctx, s.services.Pool, environment, namespace)
}
