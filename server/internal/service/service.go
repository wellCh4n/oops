// Package service holds the use cases. It sits between the HTTP layer, which
// only translates requests and responses, and the store and k8s packages, which
// only talk to MySQL and Kubernetes. Nothing here imports net/http, and nothing
// here imports client-go directly — Kubernetes is reached through the gateways
// in internal/k8s.
package service

import (
	"context"

	"github.com/wellch4n/oops/server/internal/config"
	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/gitremote"
	"github.com/wellch4n/oops/server/internal/k8s"
	"github.com/wellch4n/oops/server/internal/k8s/ide"
	"github.com/wellch4n/oops/server/internal/k8s/podfs"
	"github.com/wellch4n/oops/server/internal/k8s/sandbox"
	"github.com/wellch4n/oops/server/internal/objectstorage"
	"github.com/wellch4n/oops/server/internal/prometheus"
	"github.com/wellch4n/oops/server/internal/store"
)

// Notifier delivers a pipeline or alert message to a person. It is an interface
// so the Feishu client stays optional: with no provider configured the whole
// notification path becomes a no-op rather than a branch in every caller.
type Notifier interface {
	Notify(ctx context.Context, userID, title, body string)
}

// nopNotifier is what runs when no external provider is configured.
type nopNotifier struct{}

func (nopNotifier) Notify(context.Context, string, string, string) {}

// Services is the wiring root: one instance holds every use case, and the HTTP
// layer reaches all of them through it.
type Services struct {
	Config *config.Config
	Store  *store.Store
	Pool   *k8s.Pool

	Runtime  *k8s.RuntimeGateway
	Metrics  *k8s.MetricsGateway
	Configs  *k8s.ConfigMapGateway
	Expert   *k8s.ExpertConfigGateway
	Sandbox  *sandbox.Gateway
	PodFiles *podfs.Gateway
	IDE      *ide.Gateway

	Storage    *objectstorage.Service
	Notifier   Notifier
	Branches   *gitremote.Lister
	Prometheus *prometheus.Client

	Users        *UserService
	Namespaces   *NamespaceService
	Environments *EnvironmentService
	Domains      *DomainService
	Applications *ApplicationService
	Pipelines    *PipelineService
	Deployments  *DeploymentService
	Cluster      *ClusterService
	Assets       *AssetService
	Sandboxes    *SandboxService
	PodFS        *PodFSService
	IDEs         *IDEService
	ConfigMaps   *ConfigMapService
	ExternalAuth *ExternalAuthService
}

// New wires the services. notifier may be nil, which turns notifications into
// no-ops; storage is always present and reports "not configured" itself when the
// feature is off, so callers need no nil checks.
func New(cfg *config.Config, db *store.Store, pool *k8s.Pool, storage *objectstorage.Service, notifier Notifier) *Services {
	if notifier == nil {
		notifier = nopNotifier{}
	}
	s := &Services{
		Config:   cfg,
		Store:    db,
		Pool:     pool,
		Runtime:  k8s.NewRuntimeGateway(pool),
		Metrics:  k8s.NewMetricsGateway(pool),
		Configs:  k8s.NewConfigMapGateway(pool),
		Expert:   k8s.NewExpertConfigGateway(pool),
		PodFiles: podfs.New(pool),
		Storage:  storage,
		Notifier: notifier,
		Branches: gitremote.NewLister(),
	}
	s.Prometheus = prometheus.NewClient(pool, cfg.Metrics.History.Backend)
	s.Sandbox = sandbox.New(pool)
	// The IDE gateway is absent, not merely idle, when the feature is off — the
	// same shape as the Java @ConditionalOnProperty.
	if cfg.IDE.Enabled {
		s.IDE = ide.New(pool, ide.Options{
			Domain:       cfg.IDE.Domain,
			HTTPS:        cfg.IDE.HTTPS,
			Image:        cfg.IDE.Image,
			Middlewares:  cfg.IDE.Middlewares,
			CloneImage:   cfg.Pipeline.Images.Clone,
			CertResolver: cfg.Ingress.CertResolver,
		})
	}

	s.Users = &UserService{store: db}
	s.Namespaces = &NamespaceService{store: db}
	s.Environments = &EnvironmentService{store: db, pool: pool}
	s.Domains = &DomainService{store: db}
	s.Applications = &ApplicationService{services: s}
	s.Pipelines = &PipelineService{services: s}
	s.Deployments = &DeploymentService{services: s}
	s.Cluster = &ClusterService{services: s}
	s.Assets = &AssetService{services: s}
	s.Sandboxes = &SandboxService{services: s}
	s.PodFS = &PodFSService{services: s}
	s.IDEs = &IDEService{services: s}
	s.ConfigMaps = &ConfigMapService{services: s}
	s.ExternalAuth = &ExternalAuthService{services: s, providers: map[string]ExternalAuthProvider{}}
	return s
}

// environmentByName resolves an environment reference — the name every other
// table stores — or reports the error the UI shows.
func (s *Services) environmentByName(ctx context.Context, name string) (*domain.Environment, error) {
	if name == "" {
		return nil, domain.Biz("Environment is required")
	}
	environment, err := s.Store.Environments().FindByName(ctx, name)
	if err != nil {
		return nil, err
	}
	if environment == nil {
		return nil, domain.Bizf("Environment not found: %s", name)
	}
	return environment, nil
}

// operator loads the caller as a domain operator, for the ownership checks.
func (s *Services) operator(ctx context.Context, userID string) (*domain.Operator, error) {
	if userID == "" {
		return nil, nil
	}
	user, err := s.Store.Users().FindByID(ctx, userID)
	if err != nil || user == nil {
		return nil, err
	}
	return user.ToOperator(), nil
}
