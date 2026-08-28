package store

import (
	"context"
	"errors"
)

// The per-application config tables all follow one shape: identity columns
// plus JSON blob columns (JSONField, the GORM counterpart of the Java
// @AttributeConverter) whose field names match the Jackson output.

var ErrNotFound = errors.New("not found")

type DockerFileConfig struct {
	Type    *string `json:"type"`
	Path    *string `json:"path"`
	Content *string `json:"content"`
}

type BuildEnvironmentConfig struct {
	Environment  *string `json:"environment"`
	BuildCommand *string `json:"buildCommand"`
}

// sourceConfigBlob is the source_config JSON with its type discriminator.
type sourceConfigBlob struct {
	Type       string  `json:"type"`
	Repository *string `json:"repository,omitempty"`
}

type buildConfigRecord struct {
	ID                 string
	CreatedTime        *LocalDateTime
	Namespace          string
	ApplicationName    string
	SourceType         *string
	SourceConfig       JSONField[sourceConfigBlob]  `gorm:"column:source_config"`
	DockerFileConfig   JSONField[*DockerFileConfig] `gorm:"column:docker_file_config"`
	BuildImage         *string
	EnvironmentConfigs JSONField[[]BuildEnvironmentConfig] `gorm:"column:environment_configs"`
}

func (buildConfigRecord) TableName() string { return "application_build_config" }

// BuildConfigView mirrors ApplicationConfigDto.BuildConfig.
type BuildConfigView struct {
	ID                 string                   `json:"id"`
	CreatedTime        *LocalDateTime           `json:"createdTime"`
	Namespace          string                   `json:"namespace"`
	ApplicationName    string                   `json:"applicationName"`
	SourceType         *string                  `json:"sourceType"`
	Repository         *string                  `json:"repository"`
	DockerFileConfig   *DockerFileConfig        `json:"dockerFileConfig"`
	BuildImage         *string                  `json:"buildImage"`
	EnvironmentConfigs []BuildEnvironmentConfig `json:"environmentConfigs"`
}

func (s *Store) FindBuildConfig(ctx context.Context, namespace, applicationName string) (*BuildConfigView, error) {
	var record buildConfigRecord
	err := s.orm.WithContext(ctx).
		Where("namespace = ? AND application_name = ?", namespace, applicationName).
		First(&record).Error
	if err != nil {
		return nil, notFound(err)
	}
	view := &BuildConfigView{
		ID:              record.ID,
		CreatedTime:     record.CreatedTime,
		Namespace:       record.Namespace,
		ApplicationName: record.ApplicationName,
		SourceType:      record.SourceType,
		BuildImage:      record.BuildImage,
	}
	// The repository accessor reads the GIT variant of the source_config blob.
	if record.SourceConfig.Valid {
		view.Repository = record.SourceConfig.Data.Repository
	}
	if record.DockerFileConfig.Valid {
		view.DockerFileConfig = record.DockerFileConfig.Data
	}
	if record.EnvironmentConfigs.Valid {
		view.EnvironmentConfigs = record.EnvironmentConfigs.Data
	}
	return view, nil
}

func (s *Store) FindBuildEnvironmentConfigs(ctx context.Context, namespace, applicationName string) ([]BuildEnvironmentConfig, error) {
	buildConfig, err := s.FindBuildConfig(ctx, namespace, applicationName)
	if errors.Is(err, ErrNotFound) {
		return []BuildEnvironmentConfig{}, nil
	}
	if err != nil {
		return nil, err
	}
	if buildConfig.EnvironmentConfigs == nil {
		return []BuildEnvironmentConfig{}, nil
	}
	return buildConfig.EnvironmentConfigs, nil
}

type RuntimeEnvironmentConfig struct {
	Environment   *string `json:"environment"`
	CPURequest    *string `json:"cpuRequest"`
	CPULimit      *string `json:"cpuLimit"`
	MemoryRequest *string `json:"memoryRequest"`
	MemoryLimit   *string `json:"memoryLimit"`
	Replicas      *int    `json:"replicas"`
}

type Probe struct {
	Enabled             *bool   `json:"enabled"`
	Path                *string `json:"path"`
	InitialDelaySeconds *int    `json:"initialDelaySeconds"`
	PeriodSeconds       *int    `json:"periodSeconds"`
	TimeoutSeconds      *int    `json:"timeoutSeconds"`
	FailureThreshold    *int    `json:"failureThreshold"`
}

type HealthCheck struct {
	Liveness  *Probe `json:"liveness"`
	Readiness *Probe `json:"readiness"`
}

type runtimeSpecRecord struct {
	ID                 string
	CreatedTime        *LocalDateTime
	Namespace          string
	ApplicationName    string
	EnvironmentConfigs JSONField[[]RuntimeEnvironmentConfig] `gorm:"column:environment_configs"`
	HealthCheck        JSONField[*HealthCheck]               `gorm:"column:health_check"`
}

func (runtimeSpecRecord) TableName() string { return "application_runtime_spec" }

// RuntimeSpecView mirrors ApplicationConfigDto.RuntimeSpec.
type RuntimeSpecView struct {
	ID                 *string                    `json:"id"`
	CreatedTime        *LocalDateTime             `json:"createdTime"`
	Namespace          string                     `json:"namespace"`
	ApplicationName    string                     `json:"applicationName"`
	EnvironmentConfigs []RuntimeEnvironmentConfig `json:"environmentConfigs"`
	HealthCheck        *HealthCheck               `json:"healthCheck"`
}

// DefaultRuntimeSpec matches ApplicationService.defaultRuntimeSpec: an empty
// spec whose probes carry the Java-side defaults (disabled, path "/",
// 30/10/3/3), returned when the application has no stored spec yet.
func DefaultRuntimeSpec(namespace, applicationName string) *RuntimeSpecView {
	defaultProbe := func() *Probe {
		probe := &Probe{}
		normalizeProbe(probe)
		return probe
	}
	return &RuntimeSpecView{
		Namespace:          namespace,
		ApplicationName:    applicationName,
		EnvironmentConfigs: []RuntimeEnvironmentConfig{},
		HealthCheck:        &HealthCheck{Liveness: defaultProbe(), Readiness: defaultProbe()},
	}
}

func (s *Store) FindRuntimeSpec(ctx context.Context, namespace, applicationName string) (*RuntimeSpecView, error) {
	var record runtimeSpecRecord
	err := s.orm.WithContext(ctx).
		Where("namespace = ? AND application_name = ?", namespace, applicationName).
		First(&record).Error
	if err != nil {
		return nil, notFound(err)
	}
	view := &RuntimeSpecView{
		ID:              &record.ID,
		CreatedTime:     record.CreatedTime,
		Namespace:       record.Namespace,
		ApplicationName: record.ApplicationName,
	}
	if record.EnvironmentConfigs.Valid {
		view.EnvironmentConfigs = record.EnvironmentConfigs.Data
	}
	if record.HealthCheck.Valid {
		view.HealthCheck = record.HealthCheck.Data
	}
	normalizeHealthCheck(view.HealthCheck)
	return view, nil
}

// normalizeHealthCheck fills the Java entity's field defaults for keys absent
// from the stored JSON (Jackson keeps the initializers: enabled=false, path
// "/", 30/10/3/3), so both backends render the same probe.
func normalizeHealthCheck(healthCheck *HealthCheck) {
	if healthCheck == nil {
		return
	}
	normalizeProbe(healthCheck.Liveness)
	normalizeProbe(healthCheck.Readiness)
}

func normalizeProbe(probe *Probe) {
	if probe == nil {
		return
	}
	setBool := func(target **bool, fallback bool) {
		if *target == nil {
			value := fallback
			*target = &value
		}
	}
	setString := func(target **string, fallback string) {
		if *target == nil {
			value := fallback
			*target = &value
		}
	}
	setInt := func(target **int, fallback int) {
		if *target == nil {
			value := fallback
			*target = &value
		}
	}
	setBool(&probe.Enabled, false)
	setString(&probe.Path, "/")
	setInt(&probe.InitialDelaySeconds, 30)
	setInt(&probe.PeriodSeconds, 10)
	setInt(&probe.TimeoutSeconds, 3)
	setInt(&probe.FailureThreshold, 3)
}

// ListAllRuntimeSpecs backs the resource-alert target collection.
func (s *Store) ListAllRuntimeSpecs(ctx context.Context) ([]RuntimeSpecView, error) {
	var records []runtimeSpecRecord
	if err := s.orm.WithContext(ctx).Find(&records).Error; err != nil {
		return nil, err
	}
	specs := make([]RuntimeSpecView, 0, len(records))
	for i := range records {
		record := &records[i]
		view := RuntimeSpecView{
			ID:              &record.ID,
			CreatedTime:     record.CreatedTime,
			Namespace:       record.Namespace,
			ApplicationName: record.ApplicationName,
		}
		if record.EnvironmentConfigs.Valid {
			view.EnvironmentConfigs = record.EnvironmentConfigs.Data
		}
		specs = append(specs, view)
	}
	return specs, nil
}

type serviceEnvironmentConfigRow struct {
	Environment           *string `json:"environment"`
	Host                  *string `json:"host"`
	HTTPS                 *bool   `json:"https"`
	BasicAuthEnabled      *bool   `json:"basicAuthEnabled"`
	BasicAuthUsername     *string `json:"basicAuthUsername"`
	BasicAuthPasswordHash *string `json:"basicAuthPasswordHash"`
}

// ServiceEnvironmentConfigStored is the persisted row shape, hash included —
// used by the deploy engine, never serialized to clients.
type ServiceEnvironmentConfigStored = serviceEnvironmentConfigRow

// ServiceEnvironmentConfig is the outbound form: the stored hash never leaves,
// only the basicAuthPasswordSet marker does (see ServiceEnvironmentConfig in
// ApplicationConfigDto).
type ServiceEnvironmentConfig struct {
	Environment          *string `json:"environment"`
	Host                 *string `json:"host"`
	HTTPS                *bool   `json:"https"`
	BasicAuthEnabled     *bool   `json:"basicAuthEnabled"`
	BasicAuthUsername    *string `json:"basicAuthUsername"`
	BasicAuthPassword    *string `json:"basicAuthPassword"`
	BasicAuthPasswordSet bool    `json:"basicAuthPasswordSet"`
}

type serviceConfigRecord struct {
	ID                 string
	CreatedTime        *LocalDateTime
	Namespace          string
	ApplicationName    string
	Port               *int
	InternalPorts      JSONField[[]int]                         `gorm:"column:internal_ports"`
	EnvironmentConfigs JSONField[[]serviceEnvironmentConfigRow] `gorm:"column:environment_configs"`
}

func (serviceConfigRecord) TableName() string { return "application_service_config" }

// ServiceConfigView mirrors ApplicationConfigDto.ServiceConfig.
type ServiceConfigView struct {
	ID                       string                           `json:"id"`
	CreatedTime              *LocalDateTime                   `json:"createdTime"`
	Namespace                string                           `json:"namespace"`
	ApplicationName          string                           `json:"applicationName"`
	Port                     *int                             `json:"port"`
	InternalPorts            []int                            `json:"internalPorts"`
	EnvironmentConfigs       []ServiceEnvironmentConfig       `json:"environmentConfigs"`
	StoredEnvironmentConfigs []ServiceEnvironmentConfigStored `json:"-"`
}

func (s *Store) FindServiceConfig(ctx context.Context, namespace, applicationName string) (*ServiceConfigView, error) {
	var record serviceConfigRecord
	err := s.orm.WithContext(ctx).
		Where("namespace = ? AND application_name = ?", namespace, applicationName).
		First(&record).Error
	if err != nil {
		return nil, notFound(err)
	}
	view := &ServiceConfigView{
		ID:              record.ID,
		CreatedTime:     record.CreatedTime,
		Namespace:       record.Namespace,
		ApplicationName: record.ApplicationName,
		Port:            record.Port,
	}
	if record.InternalPorts.Valid {
		view.InternalPorts = record.InternalPorts.Data
	}
	if record.EnvironmentConfigs.Valid {
		rows := record.EnvironmentConfigs.Data
		view.StoredEnvironmentConfigs = rows
		// A stored "[]" renders as [] like the Java converter; only a NULL
		// column stays null.
		view.EnvironmentConfigs = []ServiceEnvironmentConfig{}
		for _, row := range rows {
			view.EnvironmentConfigs = append(view.EnvironmentConfigs, ServiceEnvironmentConfig{
				Environment:          row.Environment,
				Host:                 row.Host,
				HTTPS:                row.HTTPS,
				BasicAuthEnabled:     row.BasicAuthEnabled,
				BasicAuthUsername:    row.BasicAuthUsername,
				BasicAuthPasswordSet: row.BasicAuthPasswordHash != nil && *row.BasicAuthPasswordHash != "",
			})
		}
	}
	return view, nil
}

type ExpertEnvironmentConfig struct {
	Environment             *string  `json:"environment"`
	ServiceAccountName      *string  `json:"serviceAccountName"`
	Priority                *string  `json:"priority"`
	ScheduledRestartEnabled bool     `json:"scheduledRestartEnabled"`
	ScheduledRestartCron    *string  `json:"scheduledRestartCron"`
	NodeNames               []string `json:"nodeNames"`
}

type expertConfigRecord struct {
	ID                 string
	CreatedTime        *LocalDateTime
	Namespace          string
	ApplicationName    string
	EnvironmentConfigs JSONField[[]ExpertEnvironmentConfig] `gorm:"column:environment_configs"`
}

func (expertConfigRecord) TableName() string { return "application_expert_config" }

// ExpertConfigView mirrors ApplicationConfigDto.ExpertConfig.
type ExpertConfigView struct {
	ID                 *string                   `json:"id"`
	CreatedTime        *LocalDateTime            `json:"createdTime"`
	Namespace          string                    `json:"namespace"`
	ApplicationName    string                    `json:"applicationName"`
	EnvironmentConfigs []ExpertEnvironmentConfig `json:"environmentConfigs"`
}

func expertRecordToView(record *expertConfigRecord) ExpertConfigView {
	view := ExpertConfigView{
		ID:              &record.ID,
		CreatedTime:     record.CreatedTime,
		Namespace:       record.Namespace,
		ApplicationName: record.ApplicationName,
	}
	if record.EnvironmentConfigs.Valid {
		view.EnvironmentConfigs = record.EnvironmentConfigs.Data
	}
	return view
}

func (s *Store) FindExpertConfig(ctx context.Context, namespace, applicationName string) (*ExpertConfigView, error) {
	var record expertConfigRecord
	err := s.orm.WithContext(ctx).
		Where("namespace = ? AND application_name = ?", namespace, applicationName).
		First(&record).Error
	if err != nil {
		return nil, notFound(err)
	}
	view := expertRecordToView(&record)
	return &view, nil
}

// ListAllExpertConfigs backs the scheduled-restart scan.
func (s *Store) ListAllExpertConfigs(ctx context.Context) ([]ExpertConfigView, error) {
	var records []expertConfigRecord
	if err := s.orm.WithContext(ctx).Find(&records).Error; err != nil {
		return nil, err
	}
	configs := make([]ExpertConfigView, 0, len(records))
	for i := range records {
		configs = append(configs, expertRecordToView(&records[i]))
	}
	return configs, nil
}

// EnvironmentBinding mirrors ApplicationConfigDto.EnvironmentBinding.
type EnvironmentBinding struct {
	ID              string         `json:"id"`
	CreatedTime     *LocalDateTime `json:"createdTime"`
	Namespace       string         `json:"namespace"`
	ApplicationName string         `json:"applicationName"`
	Environment     string         `json:"environment"`
}

func (EnvironmentBinding) TableName() string { return "application_environment" }

// ListEnvironmentBindings filters to environments that still exist, like
// getApplicationEnvironments does.
func (s *Store) ListEnvironmentBindings(ctx context.Context, namespace, applicationName string) ([]EnvironmentBinding, error) {
	bindings := []EnvironmentBinding{}
	err := s.orm.WithContext(ctx).
		Joins("JOIN environment ON environment.name = application_environment.environment").
		Where("application_environment.namespace = ? AND application_environment.application_name = ?",
			namespace, applicationName).
		Order("application_environment.created_time").
		Find(&bindings).Error
	return bindings, err
}

func (s *Store) FindApplication(ctx context.Context, namespace, name string) (*Application, error) {
	var application Application
	err := s.orm.WithContext(ctx).
		Where("namespace = ? AND name = ?", namespace, name).
		First(&application).Error
	return &application, notFound(err)
}
