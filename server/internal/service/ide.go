package service

import (
	"context"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/k8s/ide"
)

// IDEService manages code-server instances. The gateway is absent when the
// feature is off, so every method reports that rather than dereferencing nil.
type IDEService struct {
	services *Services
}

func (s *IDEService) gateway() (*ide.Gateway, error) {
	if s.services.IDE == nil {
		return nil, domain.Biz("IDE is not enabled")
	}
	return s.services.IDE, nil
}

func (s *IDEService) List(ctx context.Context, environmentName, applicationName string) ([]ide.Instance, error) {
	gateway, err := s.gateway()
	if err != nil {
		return nil, err
	}
	environment, err := s.services.environmentByName(ctx, environmentName)
	if err != nil {
		return nil, err
	}
	return gateway.List(ctx, environment, applicationName)
}

// DefaultConfig is the settings a new IDE starts from: the packaged defaults,
// overridden by the environment's own ide-config ConfigMap.
func (s *IDEService) DefaultConfig(ctx context.Context, environmentName string) (ide.Config, error) {
	gateway, err := s.gateway()
	if err != nil {
		return ide.Config{}, err
	}
	environment, err := s.services.environmentByName(ctx, environmentName)
	if err != nil {
		return ide.Config{}, err
	}
	return gateway.DefaultConfig(ctx, environment)
}

// Create starts an IDE for one application, cloning its repository into it.
func (s *IDEService) Create(ctx context.Context, environmentName, namespace, applicationName string, request ide.CreateRequest) (string, error) {
	gateway, err := s.gateway()
	if err != nil {
		return "", err
	}
	environment, err := s.services.environmentByName(ctx, environmentName)
	if err != nil {
		return "", err
	}
	application, err := s.services.Store.Applications().FindAggregate(ctx, namespace, applicationName)
	if err != nil {
		return "", err
	}
	if application == nil {
		return "", domain.Biz("Application not found")
	}
	var repository *string
	if application.BuildConfig != nil {
		repository = application.BuildConfig.Repository()
	}
	return gateway.Create(ctx, environment, namespace, applicationName, repository, request)
}

// Delete removes an IDE. Its Service and IngressRoute go with it through the
// owner reference, so only the StatefulSet is deleted here.
func (s *IDEService) Delete(ctx context.Context, environmentName, name string) error {
	gateway, err := s.gateway()
	if err != nil {
		return err
	}
	environment, err := s.services.environmentByName(ctx, environmentName)
	if err != nil {
		return err
	}
	return gateway.Delete(ctx, environment, name)
}
