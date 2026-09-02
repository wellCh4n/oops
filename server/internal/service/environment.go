package service

import (
	"context"
	"strings"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/k8s"
	"github.com/wellch4n/oops/server/internal/store"
)

// EnvironmentService manages the cluster records. Every write re-syncs the
// registry and git credentials into the work namespace, because the namespace or
// the cluster itself may be what changed.
type EnvironmentService struct {
	store *store.Store
	pool  *k8s.Pool
}

func (s *EnvironmentService) gateway() *k8s.EnvironmentGateway {
	return k8s.NewEnvironmentGateway(s.pool)
}

func (s *EnvironmentService) List(ctx context.Context) ([]domain.Environment, error) {
	return s.store.Environments().FindAll(ctx)
}

func (s *EnvironmentService) FindByID(ctx context.Context, id string) (*domain.Environment, error) {
	return s.store.Environments().FindByID(ctx, id)
}

func (s *EnvironmentService) FindByName(ctx context.Context, name string) (*domain.Environment, error) {
	return s.store.Environments().FindByName(ctx, name)
}

// Create registers a cluster. The name is the reference every other table
// stores, so it is validated and must be unique.
func (s *EnvironmentService) Create(ctx context.Context, environment *domain.Environment) (*domain.Environment, error) {
	if err := domain.CheckEnvironmentName(environment.Name); err != nil {
		return nil, err
	}
	existing, err := s.store.Environments().FindByName(ctx, environment.Name)
	if err != nil {
		return nil, err
	}
	if existing != nil {
		return nil, domain.Bizf("Environment already exists: %s", environment.Name)
	}
	if err := s.syncCredentials(ctx, environment); err != nil {
		return nil, err
	}
	return s.store.Environments().Save(ctx, environment)
}

// UpdateCluster replaces the API server, work namespace and build storage class.
func (s *EnvironmentService) UpdateCluster(ctx context.Context, id string, update *domain.Environment) error {
	existing, err := s.requireByID(ctx, id)
	if err != nil {
		return err
	}
	existing.KubernetesApiServer = update.KubernetesApiServer
	existing.WorkNamespace = update.WorkNamespace
	existing.BuildStorageClass = update.BuildStorageClass
	if err := s.syncCredentials(ctx, existing); err != nil {
		return err
	}
	_, err = s.store.Environments().Save(ctx, existing)
	return err
}

// UpdateCredentials replaces the registry and git credentials.
func (s *EnvironmentService) UpdateCredentials(ctx context.Context, id string, update *domain.Environment) error {
	existing, err := s.requireByID(ctx, id)
	if err != nil {
		return err
	}
	existing.ImageRepository = update.ImageRepository
	existing.GitCredential = update.GitCredential
	if err := s.syncCredentials(ctx, existing); err != nil {
		return err
	}
	_, err = s.store.Environments().Save(ctx, existing)
	return err
}

func (s *EnvironmentService) Delete(ctx context.Context, id string) error {
	return s.store.Environments().DeleteByID(ctx, id)
}

func (s *EnvironmentService) requireByID(ctx context.Context, id string) (*domain.Environment, error) {
	existing, err := s.store.Environments().FindByID(ctx, id)
	if err != nil {
		return nil, err
	}
	if existing == nil {
		return nil, domain.Bizf("Environment with id %s does not exist.", id)
	}
	return existing, nil
}

// syncCredentials pushes both Secrets into the work namespace. A failure fails
// the write: an environment whose stored credentials never reached the cluster
// would fail later at build time, where the cause is much harder to see.
func (s *EnvironmentService) syncCredentials(ctx context.Context, environment *domain.Environment) error {
	gateway := s.gateway()
	if err := gateway.SyncImagePullSecret(ctx, environment); err != nil {
		return domain.BizWrap(err.Error(), err)
	}
	if err := gateway.SyncGitCredentialSecret(ctx, environment); err != nil {
		return domain.BizWrap(err.Error(), err)
	}
	return nil
}

// KubernetesValidation is the answer to "can OOPS use this cluster?".
type KubernetesValidation struct {
	Success bool   `json:"success"`
	Status  string `json:"status"` // VALID, CONNECTION_FAILED, NAMESPACE_MISSING, ERROR
	Message string `json:"message"`
}

// ValidateKubernetes probes connectivity and, when given one, the work
// namespace. A missing namespace is reported separately from a failed
// connection so the UI can offer to create it.
func (s *EnvironmentService) ValidateKubernetes(ctx context.Context, apiServer *domain.KubernetesApiServer, workNamespace string) KubernetesValidation {
	gateway := s.gateway()
	if apiServer == nil || !gateway.CanConnect(ctx, apiServer) {
		return KubernetesValidation{false, "CONNECTION_FAILED", "Unable to connect to Kubernetes API Server"}
	}
	if strings.TrimSpace(workNamespace) == "" {
		return KubernetesValidation{true, "VALID", "Connection successful"}
	}
	exists, err := gateway.NamespaceExists(ctx, apiServer, workNamespace)
	if err != nil {
		return KubernetesValidation{false, "ERROR", "Validation failed: " + err.Error()}
	}
	if !exists {
		return KubernetesValidation{false, "NAMESPACE_MISSING", "Work namespace does not exist"}
	}
	return KubernetesValidation{true, "VALID", "Validation passed"}
}

// CreateKubernetesNamespace creates the work namespace in the target cluster.
func (s *EnvironmentService) CreateKubernetesNamespace(ctx context.Context, apiServer *domain.KubernetesApiServer, workNamespace string) error {
	if err := s.gateway().CreateNamespace(ctx, apiServer, workNamespace); err != nil {
		return domain.BizWrap("Failed to create work namespace: "+err.Error(), err)
	}
	return nil
}

// ValidateImageRepository reports whether the registry accepts the credentials.
func (s *EnvironmentService) ValidateImageRepository(ctx context.Context, repository *domain.ImageRepository) bool {
	return s.gateway().IsImageRepositoryValid(ctx, repository)
}
