package service

import (
	"context"
	"errors"
	"log/slog"
	"strings"
	"time"

	"github.com/wellch4n/oops/server/internal/crypto"
	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/k8s"
	"github.com/wellch4n/oops/server/internal/store"
)

// ApplicationService is the application aggregate and everything hanging off it:
// the profile, the four per-application configs, the environment bindings, and
// the read-only views of what those produced in the cluster.
type ApplicationService struct {
	services *Services
}

func (s *ApplicationService) repo() *store.ApplicationRepository {
	return s.services.Store.Applications()
}

// requireAggregate loads the whole application or reports it missing.
func (s *ApplicationService) requireAggregate(ctx context.Context, namespace, name string) (*domain.Application, error) {
	application, err := s.repo().FindAggregate(ctx, namespace, name)
	if err != nil {
		return nil, err
	}
	if application == nil {
		return nil, domain.Biz("Application not found")
	}
	return application, nil
}

// ---------------------------------------------------------------------------
// profile

func (s *ApplicationService) Get(ctx context.Context, namespace, name string) (*ApplicationView, error) {
	application, err := s.repo().FindAggregate(ctx, namespace, name)
	if err != nil || application == nil {
		return nil, err
	}
	views, err := s.toViews(ctx, []domain.Application{*application})
	if err != nil || len(views) == 0 {
		return nil, err
	}
	return &views[0], nil
}

// List is the paged application list. ownerOnly narrows it to the caller's own.
func (s *ApplicationService) List(ctx context.Context, namespace, keyword string, page, size int, currentUserID string, ownerOnly bool) (store.Page[ApplicationView], error) {
	var ownerID *string
	if ownerOnly && currentUserID != "" {
		ownerID = &currentUserID
	}
	result, err := s.repo().FindPageOrderedByOwner(ctx, namespace, keyword, currentUserID, ownerID, page, size)
	if err != nil {
		return store.Page[ApplicationView]{}, err
	}
	views, err := s.toViews(ctx, result.Data)
	if err != nil {
		return store.Page[ApplicationView]{}, err
	}
	return store.Page[ApplicationView]{
		Total: result.Total, Data: views, Size: result.Size, TotalPages: result.TotalPages,
	}, nil
}

// Search backs the command palette: a cross-namespace name match.
//
// size caps the results rather than paging them, and size=0 means none rather
// than unlimited — the opposite convention would flood the palette on an empty
// query. The default of 5 belongs to the HTTP layer, so nothing is re-defaulted
// here.
func (s *ApplicationService) Search(ctx context.Context, keyword string, size int) ([]ApplicationView, error) {
	if size <= 0 {
		return []ApplicationView{}, nil
	}
	applications, err := s.repo().FindByNameContainingIgnoreCase(ctx, keyword)
	if err != nil {
		return nil, err
	}
	if len(applications) > size {
		applications = applications[:size]
	}
	return s.toViews(ctx, applications)
}

// toViews resolves the owner and collaborator names for a batch of applications
// in one query rather than one per row.
func (s *ApplicationService) toViews(ctx context.Context, applications []domain.Application) ([]ApplicationView, error) {
	if len(applications) == 0 {
		return []ApplicationView{}, nil
	}
	// A listing query returns only the root rows, so the source type and the
	// collaborators have to be fetched for the batch.
	names := make([]string, 0, len(applications))
	userIDs := map[string]bool{}
	for _, application := range applications {
		names = append(names, application.Name)
		if owner := domain.Deref(application.Owner); owner != "" {
			userIDs[owner] = true
		}
		for _, collaborator := range application.Collaborators {
			userIDs[collaborator.UserID] = true
		}
	}
	namespaces := map[string]bool{}
	for _, application := range applications {
		namespaces[application.Namespace] = true
	}
	buildConfigs, err := s.repo().FindBuildConfigsIn(ctx, keysOf(namespaces), names)
	if err != nil {
		return nil, err
	}
	sourceTypes := map[string]domain.ApplicationSourceType{}
	for _, config := range buildConfigs {
		sourceTypes[config.Namespace+"/"+config.ApplicationName] = config.EffectiveSourceType()
	}

	usernames, err := s.services.Users.UsernamesByID(ctx, keysOf(userIDs))
	if err != nil {
		return nil, err
	}

	views := make([]ApplicationView, 0, len(applications))
	for _, application := range applications {
		sourceType, known := sourceTypes[application.Namespace+"/"+application.Name]
		if !known {
			sourceType = domain.SourceGit
		}
		collaborators := application.CollaboratorUserIDs()
		collaboratorNames := map[string]string{}
		for _, id := range collaborators {
			if name, ok := usernames[id]; ok {
				collaboratorNames[id] = name
			}
		}
		var ownerName *string
		if name, ok := usernames[domain.Deref(application.Owner)]; ok {
			ownerName = &name
		}
		views = append(views, ApplicationView{
			ID: application.ID, CreatedTime: application.CreatedTime, Name: application.Name,
			Description: application.Description, Icon: application.Icon, Namespace: application.Namespace,
			Owner: application.Owner, OwnerName: ownerName,
			Collaborators: collaborators, CollaboratorName: collaboratorNames, SourceType: sourceType,
		})
	}
	return views, nil
}

func keysOf(set map[string]bool) []string {
	keys := make([]string, 0, len(set))
	for key := range set {
		keys = append(keys, key)
	}
	return keys
}

// Create adds an application, stamping the caller as its owner.
func (s *ApplicationService) Create(ctx context.Context, namespace string, request ProfileRequest, creatorUserID string) (string, error) {
	if err := domain.CheckResourceName(request.Name); err != nil {
		return "", err
	}
	icon, err := domain.NormalizeIcon(request.Icon)
	if err != nil {
		return "", err
	}
	owner, err := s.normalizeOwner(ctx, creatorUserID)
	if err != nil {
		return "", err
	}
	application := &domain.Application{
		Name: request.Name, Description: request.Description, Icon: icon,
		Namespace: namespace, Owner: owner,
	}
	saved, err := s.repo().SaveApplicationRow(ctx, application)
	if errors.Is(err, store.ErrDuplicate) {
		return "", domain.Biz("Application name already exists")
	}
	if err != nil {
		return "", err
	}
	return saved.ID, nil
}

// Update rewrites the profile. Collaborators are part of it: a nil list leaves
// them untouched, a present one replaces them wholesale.
func (s *ApplicationService) Update(ctx context.Context, namespace, name string, request ProfileRequest) error {
	application, err := s.requireAggregate(ctx, namespace, name)
	if err != nil {
		return err
	}
	icon, err := domain.NormalizeIcon(request.Icon)
	if err != nil {
		return err
	}
	application.Description = request.Description
	application.Icon = icon
	if request.Collaborators != nil {
		ids := domain.NormalizeCollaboratorIDs(request.Collaborators, application.Owner)
		collaborators := make([]domain.ApplicationCollaborator, 0, len(ids))
		for _, id := range ids {
			collaborators = append(collaborators, domain.ApplicationCollaborator{
				Namespace: namespace, ApplicationName: name, UserID: id,
			})
		}
		application.Collaborators = collaborators
	}
	// Only the profile is being written, so the untouched children stay nil and
	// SaveAggregate leaves their rows alone.
	profile := &domain.Application{
		ID: application.ID, CreatedTime: application.CreatedTime, Name: application.Name,
		Description: application.Description, Icon: application.Icon,
		Namespace: application.Namespace, Owner: application.Owner,
		Collaborators: application.Collaborators,
	}
	_, err = s.repo().SaveAggregate(ctx, profile)
	return err
}

// Delete removes the application and everything it created in every environment
// it was bound to. Only the owner and admins may do it.
func (s *ApplicationService) Delete(ctx context.Context, namespace, name, currentUserID string) error {
	application, err := s.requireAggregate(ctx, namespace, name)
	if err != nil {
		return err
	}
	operator, err := s.services.operator(ctx, currentUserID)
	if err != nil {
		return err
	}
	isAdmin := operator != nil && operator.IsAdmin()
	if !isAdmin && currentUserID != domain.Deref(application.Owner) {
		return domain.Biz("Permission denied")
	}
	for _, binding := range application.Environments {
		environment, err := s.services.Store.Environments().FindByName(ctx, binding.Environment)
		if err != nil {
			return err
		}
		if environment == nil {
			continue
		}
		if err := s.services.Runtime.DeleteWorkload(ctx, environment, namespace, name); err != nil {
			slog.Error("failed to delete the Kubernetes resources of an application",
				"namespace", namespace, "application", name, "environment", binding.Environment, "error", err)
			return domain.Biz("Application deletion failed")
		}
	}
	if err := s.services.Store.AlertStates().DeleteByApplication(ctx, namespace, name); err != nil {
		return err
	}
	return s.repo().DeleteAggregate(ctx, namespace, name)
}

func (s *ApplicationService) normalizeOwner(ctx context.Context, owner string) (*string, error) {
	if strings.TrimSpace(owner) == "" {
		return nil, nil
	}
	user, err := s.services.Store.Users().FindByID(ctx, owner)
	if err != nil {
		return nil, err
	}
	if user == nil {
		return nil, domain.Biz("Owner user not found")
	}
	return &owner, nil
}

// ---------------------------------------------------------------------------
// build config

func (s *ApplicationService) GetBuildConfig(ctx context.Context, namespace, name string) (*BuildConfigView, error) {
	application, err := s.repo().FindAggregate(ctx, namespace, name)
	if err != nil || application == nil {
		return nil, err
	}
	return buildConfigView(application.BuildConfig), nil
}

func (s *ApplicationService) UpdateBuildConfig(ctx context.Context, namespace, name string, request BuildConfigView) error {
	application, err := s.requireAggregate(ctx, namespace, name)
	if err != nil {
		return err
	}
	config := request.toDomain()
	config.Namespace = namespace
	config.ApplicationName = name
	if err := domain.ValidateBuildConfig(*config.SourceType, config.SourceConfig.Repository, config.DockerFileConfig); err != nil {
		return err
	}
	if application.BuildConfig != nil {
		config.ID = application.BuildConfig.ID
		config.CreatedTime = application.BuildConfig.CreatedTime
	}
	return s.saveChild(ctx, application, func(target *domain.Application) { target.BuildConfig = config })
}

func (s *ApplicationService) GetBuildEnvironmentConfigs(ctx context.Context, namespace, name string) ([]domain.BuildEnvironmentConfig, error) {
	application, err := s.repo().FindAggregate(ctx, namespace, name)
	if err != nil || application == nil || application.BuildConfig == nil {
		return []domain.BuildEnvironmentConfig{}, err
	}
	if application.BuildConfig.EnvironmentConfigs == nil {
		return []domain.BuildEnvironmentConfig{}, nil
	}
	return application.BuildConfig.EnvironmentConfigs, nil
}

func (s *ApplicationService) UpdateBuildEnvironmentConfigs(ctx context.Context, namespace, name string, configs []domain.BuildEnvironmentConfig) error {
	application, err := s.requireAggregate(ctx, namespace, name)
	if err != nil {
		return err
	}
	config := application.BuildConfig
	if config == nil {
		config = &domain.ApplicationBuildConfig{Namespace: namespace, ApplicationName: name}
	}
	config.EnvironmentConfigs = configs
	return s.saveChild(ctx, application, func(target *domain.Application) { target.BuildConfig = config })
}

// ---------------------------------------------------------------------------
// runtime spec

func (s *ApplicationService) GetRuntimeSpec(ctx context.Context, namespace, name string) (*RuntimeSpecView, error) {
	application, err := s.repo().FindAggregate(ctx, namespace, name)
	if err != nil || application == nil {
		return nil, err
	}
	return runtimeSpecView(application.RuntimeSpec), nil
}

// UpdateRuntimeSpec writes the health check. The per-environment resource limits
// are left alone: they have their own endpoint, and this one's request body does
// not carry them meaningfully.
func (s *ApplicationService) UpdateRuntimeSpec(ctx context.Context, namespace, name string, request RuntimeSpecView) error {
	application, err := s.requireAggregate(ctx, namespace, name)
	if err != nil {
		return err
	}
	spec := request.toDomain()
	spec.Namespace = namespace
	spec.ApplicationName = name
	healthCheck, err := domain.NormalizeHealthCheck(spec.HealthCheck)
	if err != nil {
		return err
	}
	spec.HealthCheck = healthCheck
	if application.RuntimeSpec != nil {
		spec.ID = application.RuntimeSpec.ID
		spec.CreatedTime = application.RuntimeSpec.CreatedTime
		if spec.EnvironmentConfigs == nil {
			spec.EnvironmentConfigs = application.RuntimeSpec.EnvironmentConfigs
		}
	}
	return s.saveChild(ctx, application, func(target *domain.Application) { target.RuntimeSpec = spec })
}

func (s *ApplicationService) GetRuntimeEnvironmentConfigs(ctx context.Context, namespace, name string) ([]domain.RuntimeEnvironmentConfig, error) {
	application, err := s.repo().FindAggregate(ctx, namespace, name)
	if err != nil || application == nil || application.RuntimeSpec == nil {
		return []domain.RuntimeEnvironmentConfig{}, err
	}
	if application.RuntimeSpec.EnvironmentConfigs == nil {
		return []domain.RuntimeEnvironmentConfig{}, nil
	}
	return application.RuntimeSpec.EnvironmentConfigs, nil
}

// UpdateRuntimeEnvironmentConfigs writes the resource limits, and pushes them
// straight at any environment the application is already running in — a limit
// change that only took effect on the next deploy would look like it had been
// ignored.
func (s *ApplicationService) UpdateRuntimeEnvironmentConfigs(ctx context.Context, namespace, name string, configs []domain.RuntimeEnvironmentConfig) error {
	application, err := s.requireAggregate(ctx, namespace, name)
	if err != nil {
		return err
	}
	spec := application.RuntimeSpec
	if spec == nil {
		spec = &domain.ApplicationRuntimeSpec{Namespace: namespace, ApplicationName: name}
	}
	spec.EnvironmentConfigs = configs
	if err := s.saveChild(ctx, application, func(target *domain.Application) { target.RuntimeSpec = spec }); err != nil {
		return err
	}
	for _, config := range configs {
		environmentName := domain.Deref(config.Environment)
		if environmentName == "" {
			continue
		}
		environment, err := s.services.Store.Environments().FindByName(ctx, environmentName)
		if err != nil || environment == nil {
			continue
		}
		if err := s.services.Runtime.ApplyRuntimeSpec(ctx, environment, namespace, name, config); err != nil {
			// Best effort: the application may simply not be deployed there yet.
			slog.Warn("could not apply the runtime spec to a running workload",
				"namespace", namespace, "application", name, "environment", environmentName, "error", err)
		}
	}
	return nil
}

// ---------------------------------------------------------------------------
// service config

func (s *ApplicationService) GetServiceConfig(ctx context.Context, namespace, name string) (*ServiceConfigView, error) {
	application, err := s.repo().FindAggregate(ctx, namespace, name)
	if err != nil || application == nil {
		return nil, err
	}
	return serviceConfigView(application.ServiceConfig), nil
}

// UpdateServiceConfig rewrites the whole service config, hosts included. Each
// host is checked against the managed domains and against every other
// application before anything is written.
func (s *ApplicationService) UpdateServiceConfig(ctx context.Context, namespace, name string, request ServiceConfigView) error {
	if request.EnvironmentConfigs != nil {
		managedDomains, err := s.services.Store.Domains().FindAll(ctx)
		if err != nil {
			return err
		}
		for _, host := range request.EnvironmentConfigs {
			hostname := domain.Deref(host.Host)
			if strings.TrimSpace(hostname) == "" {
				continue
			}
			if err := domain.ValidateHost(hostname); err != nil {
				return domain.BizWrap(err.Error(), err)
			}
			if err := requireDomainAllowsEnvironment(hostname, domain.Deref(host.Environment), managedDomains); err != nil {
				return err
			}
			conflict, err := s.FindHostConflict(ctx, namespace, name, hostname)
			if err != nil {
				return err
			}
			if conflict != nil {
				return domain.Bizf("Host %s is already used by environment %s / namespace %s / application %s",
					hostname, conflict.Environment, conflict.Namespace, conflict.ApplicationName)
			}
		}
	}

	application, err := s.requireAggregate(ctx, namespace, name)
	if err != nil {
		return err
	}
	hosts, err := resolveBasicAuth(application.ServiceConfig, request.EnvironmentConfigs)
	if err != nil {
		return err
	}
	config := &domain.ApplicationServiceConfig{
		Namespace: namespace, ApplicationName: name,
		Port: request.Port, InternalPorts: request.InternalPorts, EnvironmentConfigs: hosts,
	}
	if application.ServiceConfig != nil {
		config.ID = application.ServiceConfig.ID
		config.CreatedTime = application.ServiceConfig.CreatedTime
	}
	return s.saveChild(ctx, application, func(target *domain.Application) { target.ServiceConfig = config })
}

// requireDomainAllowsEnvironment stops a host being pointed at an environment
// its governing domain is not bound to — the route would come up without a
// certificate.
func requireDomainAllowsEnvironment(host, environment string, managedDomains []domain.Domain) error {
	governing := domain.FindBestDomainMatch(host, managedDomains)
	if governing == nil || governing.AllowsEnvironment(environment) {
		return nil
	}
	message := "Domain " + domain.Deref(governing.Host) + " is not available in environment " + environment
	if governing.Environment != nil {
		message += " (its environment is " + *governing.Environment + ")"
	}
	return domain.Biz(message)
}

// resolveBasicAuth turns the write-only password into a stored hash, carrying
// the existing hash forward when the request leaves the password blank. A host
// with auth turned off stores nothing at all about basic auth, rather than
// storing "disabled".
func resolveBasicAuth(existing *domain.ApplicationServiceConfig, requested []ServiceEnvironmentView) ([]domain.ServiceEnvironmentConfig, error) {
	if requested == nil {
		return nil, nil
	}
	storedHashes := map[string]string{}
	if existing != nil {
		for _, stored := range existing.EnvironmentConfigs {
			if !domain.IsBlank(stored.BasicAuthPasswordHash) {
				storedHashes[basicAuthKey(domain.Deref(stored.Environment), domain.Deref(stored.Host))] = *stored.BasicAuthPasswordHash
			}
		}
	}
	result := make([]domain.ServiceEnvironmentConfig, 0, len(requested))
	for _, item := range requested {
		config := domain.ServiceEnvironmentConfig{
			Environment: item.Environment, Host: item.Host, HTTPS: item.HTTPS,
		}
		if item.BasicAuthEnabled == nil || !*item.BasicAuthEnabled {
			result = append(result, config)
			continue
		}
		if domain.IsBlank(item.BasicAuthUsername) {
			return nil, domain.Bizf("Basic auth username is required for host %s", domain.Deref(item.Host))
		}
		config.BasicAuthEnabled = item.BasicAuthEnabled
		config.BasicAuthUsername = item.BasicAuthUsername
		if !domain.IsBlank(item.BasicAuthPassword) {
			hash, err := crypto.HashPassword(*item.BasicAuthPassword)
			if err != nil {
				return nil, err
			}
			config.BasicAuthPasswordHash = &hash
			result = append(result, config)
			continue
		}
		storedHash, found := storedHashes[basicAuthKey(domain.Deref(item.Environment), domain.Deref(item.Host))]
		if !found || strings.TrimSpace(storedHash) == "" {
			return nil, domain.Bizf("Basic auth password is required for host %s", domain.Deref(item.Host))
		}
		config.BasicAuthPasswordHash = &storedHash
		result = append(result, config)
	}
	return result, nil
}

func basicAuthKey(environment, host string) string { return environment + "\n" + host }

// FindHostConflict reports the application already serving this host, if any.
// The SQL prefilter matches the quoted host inside the JSON blob, so the exact
// comparison still has to happen here.
func (s *ApplicationService) FindHostConflict(ctx context.Context, namespace, name, host string) (*ServiceHostConflictView, error) {
	if strings.TrimSpace(host) == "" {
		return nil, nil
	}
	conflicts, err := s.repo().FindServiceConfigsByHostLikeExcludingSelf(ctx, `"`+host+`"`, namespace, name)
	if err != nil {
		return nil, err
	}
	for _, conflict := range conflicts {
		for _, item := range conflict.EnvironmentConfigs {
			if domain.Deref(item.Host) == host {
				return &ServiceHostConflictView{
					Namespace:       conflict.Namespace,
					ApplicationName: conflict.ApplicationName,
					Environment:     domain.Deref(item.Environment),
				}, nil
			}
		}
	}
	return nil, nil
}

// GetClusterDomain returns the in-cluster Service address and the external URLs.
func (s *ApplicationService) GetClusterDomain(ctx context.Context, namespace, name, environmentName string) (*ClusterDomainView, error) {
	environment, err := s.services.environmentByName(ctx, environmentName)
	if err != nil {
		return nil, err
	}
	internal, err := s.services.Runtime.FindInternalServiceDomain(ctx, environment, namespace, name)
	if err != nil {
		return nil, err
	}
	application, err := s.repo().FindAggregate(ctx, namespace, name)
	if err != nil {
		return nil, err
	}
	var external []string
	if application != nil {
		for _, host := range application.ServiceConfigOrDefault().EnvironmentConfigsFor(environmentName) {
			hostname := domain.Deref(host.Host)
			if strings.TrimSpace(hostname) == "" {
				continue
			}
			scheme := "http"
			if host.HTTPS != nil && *host.HTTPS {
				scheme = "https"
			}
			external = append(external, scheme+"://"+hostname)
		}
	}
	return &ClusterDomainView{InternalDomain: internal, ExternalDomains: external}, nil
}

// ---------------------------------------------------------------------------
// expert config

func (s *ApplicationService) GetExpertConfig(ctx context.Context, namespace, name string) (*ExpertConfigView, error) {
	application, err := s.repo().FindAggregate(ctx, namespace, name)
	if err != nil || application == nil {
		return nil, err
	}
	return expertConfigView(application.ExpertConfig), nil
}

// UpdateExpertConfig writes the advanced settings and pushes them at any
// environment already running the application.
func (s *ApplicationService) UpdateExpertConfig(ctx context.Context, namespace, name string, request ExpertConfigView) error {
	application, err := s.requireAggregate(ctx, namespace, name)
	if err != nil {
		return err
	}
	config := request.toDomain()
	config.Namespace = namespace
	config.ApplicationName = name
	for index := range config.EnvironmentConfigs {
		config.EnvironmentConfigs[index].NodeNames = domain.NormalizeNodeNames(config.EnvironmentConfigs[index].NodeNames)
	}
	if application.ExpertConfig != nil {
		config.ID = application.ExpertConfig.ID
		config.CreatedTime = application.ExpertConfig.CreatedTime
	}
	if err := s.saveChild(ctx, application, func(target *domain.Application) { target.ExpertConfig = config }); err != nil {
		return err
	}
	for _, item := range config.EnvironmentConfigs {
		environmentName := domain.Deref(item.Environment)
		if environmentName == "" {
			continue
		}
		environment, err := s.services.Store.Environments().FindByName(ctx, environmentName)
		if err != nil || environment == nil {
			continue
		}
		if err := s.services.Expert.ApplyExpertConfig(ctx, environment, namespace, name, item); err != nil {
			slog.Warn("could not apply the expert config to a running workload",
				"namespace", namespace, "application", name, "environment", environmentName, "error", err)
		}
	}
	return nil
}

// ---------------------------------------------------------------------------
// environment bindings

func (s *ApplicationService) GetEnvironments(ctx context.Context, namespace, name string) ([]EnvironmentBindingView, error) {
	application, err := s.repo().FindAggregate(ctx, namespace, name)
	if err != nil || application == nil {
		return []EnvironmentBindingView{}, err
	}
	views := make([]EnvironmentBindingView, 0, len(application.Environments))
	for _, binding := range application.Environments {
		views = append(views, EnvironmentBindingView{
			ID: binding.ID, CreatedTime: binding.CreatedTime, Namespace: binding.Namespace,
			ApplicationName: binding.ApplicationName, Environment: binding.Environment,
		})
	}
	return views, nil
}

// UpdateEnvironments replaces the bindings wholesale.
func (s *ApplicationService) UpdateEnvironments(ctx context.Context, namespace, name string, requests []EnvironmentBindingView) error {
	application, err := s.requireAggregate(ctx, namespace, name)
	if err != nil {
		return err
	}
	bindings := make([]domain.ApplicationEnvironment, 0, len(requests))
	for _, request := range requests {
		if strings.TrimSpace(request.Environment) == "" {
			continue
		}
		bindings = append(bindings, domain.ApplicationEnvironment{
			ID: request.ID, CreatedTime: request.CreatedTime,
			Namespace: namespace, ApplicationName: name, Environment: request.Environment,
		})
	}
	return s.saveChild(ctx, application, func(target *domain.Application) { target.Environments = bindings })
}

// saveChild writes one child of the aggregate, leaving the others untouched:
// SaveAggregate skips a nil child, so the update carries only what changed.
func (s *ApplicationService) saveChild(ctx context.Context, application *domain.Application, mutate func(*domain.Application)) error {
	update := &domain.Application{
		ID: application.ID, CreatedTime: application.CreatedTime, Name: application.Name,
		Description: application.Description, Icon: application.Icon,
		Namespace: application.Namespace, Owner: application.Owner,
	}
	mutate(update)
	_, err := s.repo().SaveAggregate(ctx, update)
	return err
}

// ---------------------------------------------------------------------------
// cluster views

func (s *ApplicationService) GetStatus(ctx context.Context, namespace, name, environmentName string) ([]k8s.PodStatusView, error) {
	environment, err := s.services.environmentByName(ctx, environmentName)
	if err != nil {
		return nil, err
	}
	return s.services.Runtime.GetPodStatuses(ctx, environment, namespace, name)
}

// WatchStatus streams pod status changes for the status page.
func (s *ApplicationService) WatchStatus(ctx context.Context, namespace, name, environmentName string) (<-chan []k8s.PodStatusView, <-chan error, error) {
	environment, err := s.services.environmentByName(ctx, environmentName)
	if err != nil {
		return nil, nil, err
	}
	return s.services.Runtime.WatchPodStatuses(ctx, environment, namespace, name)
}

func (s *ApplicationService) GetEvents(ctx context.Context, namespace, name, environmentName string, since *time.Time, limit int) ([]k8s.EventView, error) {
	environment, err := s.services.environmentByName(ctx, environmentName)
	if err != nil {
		return nil, err
	}
	return s.services.Runtime.GetEvents(ctx, environment, namespace, name, since, limit)
}

func (s *ApplicationService) GetResources(ctx context.Context, namespace, name, environmentName string) ([]k8s.ResourceView, error) {
	environment, err := s.services.environmentByName(ctx, environmentName)
	if err != nil {
		return nil, err
	}
	return s.services.Expert.GetApplicationResources(ctx, environment, namespace, name)
}

func (s *ApplicationService) GetMetrics(ctx context.Context, namespace, name, environmentName string) ([]k8s.PodMetricSnapshot, error) {
	environment, err := s.services.environmentByName(ctx, environmentName)
	if err != nil {
		return nil, err
	}
	return s.services.Metrics.GetPodMetrics(ctx, environment, namespace, name)
}

func (s *ApplicationService) GetCurrentImage(ctx context.Context, namespace, name, environmentName string) (string, error) {
	environment, err := s.services.environmentByName(ctx, environmentName)
	if err != nil {
		return "", err
	}
	return s.services.Runtime.FindCurrentImage(ctx, environment, namespace, name)
}

// RestartPod deletes one pod so the StatefulSet controller replaces it.
func (s *ApplicationService) RestartPod(ctx context.Context, namespace, name, pod, environmentName string) error {
	environment, err := s.services.environmentByName(ctx, environmentName)
	if err != nil {
		return err
	}
	return s.services.Runtime.RestartPod(ctx, environment, namespace, pod)
}
