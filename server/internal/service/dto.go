package service

import (
	"github.com/wellch4n/oops/server/internal/domain"
)

// The view types are the JSON the API answers with. They exist separately from
// the domain aggregates because the two differ in three places that matter:
// the aggregates carry no JSON tags (they are storage shapes), the build config
// presents its polymorphic source as a flat `repository`, and a host's basic
// auth password is write-only — only a `basicAuthPasswordSet` marker comes back.

// ApplicationView is ApplicationDto.
type ApplicationView struct {
	ID               string                       `json:"id"`
	CreatedTime      domain.LocalDateTime         `json:"createdTime"`
	Name             string                       `json:"name"`
	Description      *string                      `json:"description"`
	Icon             *string                      `json:"icon"`
	Namespace        string                       `json:"namespace"`
	Owner            *string                      `json:"owner"`
	OwnerName        *string                      `json:"ownerName"`
	Collaborators    []string                     `json:"collaborators"`
	CollaboratorName map[string]string            `json:"collaboratorNames"`
	SourceType       domain.ApplicationSourceType `json:"sourceType"`
}

// ProfileRequest is the create/update body for an application's profile.
type ProfileRequest struct {
	ID            string               `json:"id"`
	CreatedTime   domain.LocalDateTime `json:"createdTime"`
	Name          string               `json:"name"`
	Description   *string              `json:"description"`
	Icon          *string              `json:"icon"`
	Namespace     string               `json:"namespace"`
	Owner         *string              `json:"owner"`
	Collaborators []string             `json:"collaborators"`
}

// BuildConfigView flattens SourceConfig into `repository`.
type BuildConfigView struct {
	ID                 string                          `json:"id"`
	CreatedTime        domain.LocalDateTime            `json:"createdTime"`
	Namespace          string                          `json:"namespace"`
	ApplicationName    string                          `json:"applicationName"`
	SourceType         *domain.ApplicationSourceType   `json:"sourceType"`
	Repository         *string                         `json:"repository"`
	DockerFileConfig   *domain.DockerFileConfig        `json:"dockerFileConfig"`
	BuildImage         *string                         `json:"buildImage"`
	EnvironmentConfigs []domain.BuildEnvironmentConfig `json:"environmentConfigs"`
}

func buildConfigView(config *domain.ApplicationBuildConfig) *BuildConfigView {
	if config == nil {
		return nil
	}
	return &BuildConfigView{
		ID:                 config.ID,
		CreatedTime:        config.CreatedTime,
		Namespace:          config.Namespace,
		ApplicationName:    config.ApplicationName,
		SourceType:         config.SourceType,
		Repository:         config.Repository(),
		DockerFileConfig:   config.DockerFileConfig,
		BuildImage:         config.BuildImage,
		EnvironmentConfigs: config.EnvironmentConfigs,
	}
}

func (v BuildConfigView) toDomain() *domain.ApplicationBuildConfig {
	sourceType := domain.SourceGit
	if v.SourceType != nil && *v.SourceType != "" {
		sourceType = *v.SourceType
	}
	return &domain.ApplicationBuildConfig{
		ID:                 v.ID,
		CreatedTime:        v.CreatedTime,
		Namespace:          v.Namespace,
		ApplicationName:    v.ApplicationName,
		SourceType:         &sourceType,
		SourceConfig:       domain.BuildSourceConfig(sourceType, v.Repository),
		DockerFileConfig:   v.DockerFileConfig,
		BuildImage:         v.BuildImage,
		EnvironmentConfigs: v.EnvironmentConfigs,
	}
}

// RuntimeSpecView is ApplicationConfigDto.RuntimeSpec.
type RuntimeSpecView struct {
	ID                 string                            `json:"id"`
	CreatedTime        domain.LocalDateTime              `json:"createdTime"`
	Namespace          string                            `json:"namespace"`
	ApplicationName    string                            `json:"applicationName"`
	EnvironmentConfigs []domain.RuntimeEnvironmentConfig `json:"environmentConfigs"`
	HealthCheck        *domain.HealthCheck               `json:"healthCheck"`
}

func runtimeSpecView(spec *domain.ApplicationRuntimeSpec) *RuntimeSpecView {
	if spec == nil {
		return nil
	}
	return &RuntimeSpecView{
		ID: spec.ID, CreatedTime: spec.CreatedTime, Namespace: spec.Namespace,
		ApplicationName: spec.ApplicationName, EnvironmentConfigs: spec.EnvironmentConfigs,
		HealthCheck: spec.HealthCheck,
	}
}

func (v RuntimeSpecView) toDomain() *domain.ApplicationRuntimeSpec {
	return &domain.ApplicationRuntimeSpec{
		ID: v.ID, CreatedTime: v.CreatedTime, Namespace: v.Namespace,
		ApplicationName: v.ApplicationName, EnvironmentConfigs: v.EnvironmentConfigs,
		HealthCheck: v.HealthCheck,
	}
}

// ServiceEnvironmentView is a host. The stored password hash never leaves the
// server: `basicAuthPassword` is write-only and `basicAuthPasswordSet` is the
// read-only marker that one is stored.
type ServiceEnvironmentView struct {
	Environment          *string `json:"environment"`
	Host                 *string `json:"host"`
	HTTPS                *bool   `json:"https"`
	BasicAuthEnabled     *bool   `json:"basicAuthEnabled"`
	BasicAuthUsername    *string `json:"basicAuthUsername"`
	BasicAuthPassword    *string `json:"basicAuthPassword"`
	BasicAuthPasswordSet bool    `json:"basicAuthPasswordSet"`
}

func serviceEnvironmentView(config domain.ServiceEnvironmentConfig) ServiceEnvironmentView {
	return ServiceEnvironmentView{
		Environment:          config.Environment,
		Host:                 config.Host,
		HTTPS:                config.HTTPS,
		BasicAuthEnabled:     config.BasicAuthEnabled,
		BasicAuthUsername:    config.BasicAuthUsername,
		BasicAuthPasswordSet: !domain.IsBlank(config.BasicAuthPasswordHash),
	}
}

// ServiceConfigView is ApplicationConfigDto.ServiceConfig.
type ServiceConfigView struct {
	ID                 string                   `json:"id"`
	CreatedTime        domain.LocalDateTime     `json:"createdTime"`
	Namespace          string                   `json:"namespace"`
	ApplicationName    string                   `json:"applicationName"`
	Port               *int                     `json:"port"`
	InternalPorts      []int                    `json:"internalPorts"`
	EnvironmentConfigs []ServiceEnvironmentView `json:"environmentConfigs"`
}

func serviceConfigView(config *domain.ApplicationServiceConfig) *ServiceConfigView {
	if config == nil {
		return nil
	}
	var hosts []ServiceEnvironmentView
	if config.EnvironmentConfigs != nil {
		hosts = make([]ServiceEnvironmentView, 0, len(config.EnvironmentConfigs))
		for _, item := range config.EnvironmentConfigs {
			hosts = append(hosts, serviceEnvironmentView(item))
		}
	}
	return &ServiceConfigView{
		ID: config.ID, CreatedTime: config.CreatedTime, Namespace: config.Namespace,
		ApplicationName: config.ApplicationName, Port: config.Port,
		InternalPorts: config.InternalPorts, EnvironmentConfigs: hosts,
	}
}

// ExpertConfigView is ApplicationConfigDto.ExpertConfig.
type ExpertConfigView struct {
	ID                 string                           `json:"id"`
	CreatedTime        domain.LocalDateTime             `json:"createdTime"`
	Namespace          string                           `json:"namespace"`
	ApplicationName    string                           `json:"applicationName"`
	EnvironmentConfigs []domain.ExpertEnvironmentConfig `json:"environmentConfigs"`
}

func expertConfigView(config *domain.ApplicationExpertConfig) *ExpertConfigView {
	if config == nil {
		return nil
	}
	return &ExpertConfigView{
		ID: config.ID, CreatedTime: config.CreatedTime, Namespace: config.Namespace,
		ApplicationName: config.ApplicationName, EnvironmentConfigs: config.EnvironmentConfigs,
	}
}

func (v ExpertConfigView) toDomain() *domain.ApplicationExpertConfig {
	return &domain.ApplicationExpertConfig{
		ID: v.ID, CreatedTime: v.CreatedTime, Namespace: v.Namespace,
		ApplicationName: v.ApplicationName, EnvironmentConfigs: v.EnvironmentConfigs,
	}
}

// EnvironmentBindingView is one row of an application's environment bindings.
type EnvironmentBindingView struct {
	ID              string               `json:"id"`
	CreatedTime     domain.LocalDateTime `json:"createdTime"`
	Namespace       string               `json:"namespace"`
	ApplicationName string               `json:"applicationName"`
	Environment     string               `json:"environment"`
}

// ServiceHostConflictView names the application already serving a host.
type ServiceHostConflictView struct {
	Namespace       string `json:"namespace"`
	ApplicationName string `json:"applicationName"`
	Environment     string `json:"environment"`
}

// ClusterDomainView is the in-cluster Service address plus the external URLs.
type ClusterDomainView struct {
	InternalDomain  string   `json:"internalDomain"`
	ExternalDomains []string `json:"externalDomains"`
}

// PipelineView is PipelineDto.
type PipelineView struct {
	ID                     string                       `json:"id"`
	CreatedTime            domain.LocalDateTime         `json:"createdTime"`
	Namespace              string                       `json:"namespace"`
	ApplicationName        string                       `json:"applicationName"`
	Name                   string                       `json:"name"`
	Status                 domain.PipelineStatus        `json:"status"`
	Artifact               *string                      `json:"artifact"`
	Environment            string                       `json:"environment"`
	PublishType            domain.ApplicationSourceType `json:"publishType"`
	PublishConfig          *domain.PublishConfig        `json:"publishConfig"`
	DeployMode             domain.DeployMode            `json:"deployMode"`
	OperatorID             *string                      `json:"operatorId"`
	OperatorName           *string                      `json:"operatorName"`
	Message                *string                      `json:"message"`
	TriggerType            domain.PipelineTriggerType   `json:"triggerType"`
	RollbackFromPipelineID *string                      `json:"rollbackFromPipelineId"`
}

func pipelineView(pipeline *domain.Pipeline, operatorName *string) PipelineView {
	return PipelineView{
		ID: pipeline.ID, CreatedTime: pipeline.CreatedTime, Namespace: pipeline.Namespace,
		ApplicationName: pipeline.ApplicationName, Name: pipeline.Name(), Status: pipeline.Status,
		Artifact: pipeline.Artifact, Environment: pipeline.Environment, PublishType: pipeline.PublishType,
		PublishConfig: pipeline.PublishConfig, DeployMode: pipeline.DeployMode,
		OperatorID: pipeline.OperatorID, OperatorName: operatorName, Message: pipeline.Message,
		TriggerType: pipeline.TriggerType, RollbackFromPipelineID: pipeline.RollbackFromPipelineID,
	}
}

// ActiveDeploymentView is one in-flight deployment on the application list.
type ActiveDeploymentView struct {
	Namespace       string                `json:"namespace"`
	ApplicationName string                `json:"applicationName"`
	PipelineID      string                `json:"pipelineId"`
	Environment     string                `json:"environment"`
	Status          domain.PipelineStatus `json:"status"`
	CreatedTime     domain.LocalDateTime  `json:"createdTime"`
}

// LastSuccessfulPipelineView pre-fills the deploy dialog from the last release.
type LastSuccessfulPipelineView struct {
	DeployMode    domain.DeployMode            `json:"deployMode"`
	PublishType   domain.ApplicationSourceType `json:"publishType"`
	PublishConfig *domain.PublishConfig        `json:"publishConfig"`
}

// NamespaceMigrationResult reports where an application ended up. The database
// rows always move; recreating each running workload in the target namespace is
// best effort, so a partial failure has to be visible rather than swallowed.
type NamespaceMigrationResult struct {
	SourceNamespace      string   `json:"sourceNamespace"`
	TargetNamespace      string   `json:"targetNamespace"`
	MigratedEnvironments []string `json:"migratedEnvironments"`
	FailedEnvironments   []string `json:"failedEnvironments"`
}

// FeaturesView is what the UI asks for before deciding which pages to render.
type FeaturesView struct {
	Feishu        bool    `json:"feishu"`
	IDE           bool    `json:"ide"`
	IDEHost       *string `json:"ideHost"`
	IDEHTTPS      bool    `json:"ideHttps"`
	ObjectStorage bool    `json:"objectStorage"`
}

// LoginView is what a successful login returns.
type LoginView struct {
	Token    string           `json:"token"`
	ID       string           `json:"id"`
	Username *string          `json:"username"`
	Role     *domain.UserRole `json:"role"`
}

// DomainView is DomainDto: the certificate PEM never leaves the server, only
// the fact that one is stored and what it says.
type DomainView struct {
	ID              string                 `json:"id"`
	Host            *string                `json:"host"`
	Description     *string                `json:"description"`
	HTTPS           *bool                  `json:"https"`
	CertMode        *domain.DomainCertMode `json:"certMode"`
	HasUploadedCert bool                   `json:"hasUploadedCert"`
	CertSubject     *string                `json:"certSubject"`
	CertNotAfter    domain.LocalDateTime   `json:"certNotAfter"`
	CreatedTime     domain.LocalDateTime   `json:"createdTime"`
	Environment     *string                `json:"environment"`
}

// DomainViewOf renders a domain for the API.
func DomainViewOf(record domain.Domain) DomainView {
	return DomainView{
		ID: record.ID, Host: record.Host, Description: record.Description, HTTPS: record.HTTPS,
		CertMode: record.CertMode, HasUploadedCert: !domain.IsBlank(record.CertPem),
		CertSubject: record.CertSubject, CertNotAfter: record.CertNotAfter,
		CreatedTime: record.CreatedTime, Environment: record.Environment,
	}
}

// EnvironmentView is EnvironmentDto.
type EnvironmentView struct {
	ID                  string                   `json:"id"`
	Name                string                   `json:"name"`
	KubernetesApiServer *KubernetesApiServerView `json:"kubernetesApiServer"`
	WorkNamespace       *string                  `json:"workNamespace"`
	BuildStorageClass   *string                  `json:"buildStorageClass"`
	ImageRepository     *ImageRepositoryView     `json:"imageRepository"`
	GitCredential       *domain.GitCredential    `json:"gitCredential"`
}

type KubernetesApiServerView struct {
	URL   *string `json:"url"`
	Token *string `json:"token"`
}

type ImageRepositoryView struct {
	URL      *string `json:"url"`
	Username *string `json:"username"`
	Password *string `json:"password"`
}

// EnvironmentViewOf renders an environment with its secrets intact. Callers of
// the read endpoints are already admins, and the UI edit form needs the values
// back to re-submit them.
func EnvironmentViewOf(environment domain.Environment) EnvironmentView {
	view := EnvironmentView{
		ID: environment.ID, Name: environment.Name,
		WorkNamespace: environment.WorkNamespace, BuildStorageClass: environment.BuildStorageClass,
		GitCredential: environment.GitCredential,
	}
	if environment.KubernetesApiServer != nil {
		view.KubernetesApiServer = &KubernetesApiServerView{
			URL: environment.KubernetesApiServer.URL, Token: environment.KubernetesApiServer.Token,
		}
	}
	if environment.ImageRepository != nil {
		view.ImageRepository = &ImageRepositoryView{
			URL: environment.ImageRepository.URL, Username: environment.ImageRepository.Username,
			Password: environment.ImageRepository.Password,
		}
	}
	return view
}
