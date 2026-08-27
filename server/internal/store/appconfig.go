package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
)

// The per-application config tables all follow one shape: identity columns plus
// JSON blob columns whose field names match the Jackson output, so the blobs
// pass through decode/encode without renaming.

type DockerFileConfig struct {
	Type    *string `json:"type"`
	Path    *string `json:"path"`
	Content *string `json:"content"`
}

type BuildEnvironmentConfig struct {
	EnvironmentName *string `json:"environmentName"`
	BuildCommand    *string `json:"buildCommand"`
}

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

var ErrNotFound = errors.New("not found")

func decodeJSONColumn[T any](column sql.NullString, target *T) error {
	if !column.Valid || column.String == "" {
		return nil
	}
	if err := json.Unmarshal([]byte(column.String), target); err != nil {
		return fmt.Errorf("decode json column: %w", err)
	}
	return nil
}

func (s *Store) FindBuildConfig(ctx context.Context, namespace, applicationName string) (*BuildConfigView, error) {
	var view BuildConfigView
	var sourceConfig, dockerFile, environmentConfigs sql.NullString
	err := s.db.QueryRowContext(ctx,
		`SELECT id, created_time, namespace, application_name, source_type, source_config,
		        docker_file_config, build_image, environment_configs
		 FROM application_build_config WHERE namespace = ? AND application_name = ?`,
		namespace, applicationName).
		Scan(&view.ID, &view.CreatedTime, &view.Namespace, &view.ApplicationName, &view.SourceType,
			&sourceConfig, &dockerFile, &view.BuildImage, &environmentConfigs)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	// The repository accessor reads the GIT variant of the source_config blob.
	var source struct {
		Repository *string `json:"repository"`
	}
	if err := decodeJSONColumn(sourceConfig, &source); err != nil {
		return nil, err
	}
	view.Repository = source.Repository
	if err := decodeJSONColumn(dockerFile, &view.DockerFileConfig); err != nil {
		return nil, err
	}
	if err := decodeJSONColumn(environmentConfigs, &view.EnvironmentConfigs); err != nil {
		return nil, err
	}
	return &view, nil
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
	EnvironmentName *string `json:"environmentName"`
	CPURequest      *string `json:"cpuRequest"`
	CPULimit        *string `json:"cpuLimit"`
	MemoryRequest   *string `json:"memoryRequest"`
	MemoryLimit     *string `json:"memoryLimit"`
	Replicas        *int    `json:"replicas"`
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
		enabled := false
		path := "/"
		initialDelay, period, timeout, failureThreshold := 30, 10, 3, 3
		return &Probe{
			Enabled:             &enabled,
			Path:                &path,
			InitialDelaySeconds: &initialDelay,
			PeriodSeconds:       &period,
			TimeoutSeconds:      &timeout,
			FailureThreshold:    &failureThreshold,
		}
	}
	return &RuntimeSpecView{
		Namespace:          namespace,
		ApplicationName:    applicationName,
		EnvironmentConfigs: []RuntimeEnvironmentConfig{},
		HealthCheck:        &HealthCheck{Liveness: defaultProbe(), Readiness: defaultProbe()},
	}
}

func (s *Store) FindRuntimeSpec(ctx context.Context, namespace, applicationName string) (*RuntimeSpecView, error) {
	var view RuntimeSpecView
	var environmentConfigs, healthCheck sql.NullString
	err := s.db.QueryRowContext(ctx,
		`SELECT id, created_time, namespace, application_name, environment_configs, health_check
		 FROM application_runtime_spec WHERE namespace = ? AND application_name = ?`,
		namespace, applicationName).
		Scan(&view.ID, &view.CreatedTime, &view.Namespace, &view.ApplicationName,
			&environmentConfigs, &healthCheck)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if err := decodeJSONColumn(environmentConfigs, &view.EnvironmentConfigs); err != nil {
		return nil, err
	}
	if err := decodeJSONColumn(healthCheck, &view.HealthCheck); err != nil {
		return nil, err
	}
	normalizeHealthCheck(view.HealthCheck)
	return &view, nil
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

type serviceEnvironmentConfigRow struct {
	EnvironmentName       *string `json:"environmentName"`
	Host                  *string `json:"host"`
	HTTPS                 *bool   `json:"https"`
	BasicAuthEnabled      *bool   `json:"basicAuthEnabled"`
	BasicAuthUsername     *string `json:"basicAuthUsername"`
	BasicAuthPasswordHash *string `json:"basicAuthPasswordHash"`
}

// ServiceEnvironmentConfig is the outbound form: the stored hash never leaves,
// only the basicAuthPasswordSet marker does (see ServiceEnvironmentConfig in
// ApplicationConfigDto).
type ServiceEnvironmentConfig struct {
	EnvironmentName      *string `json:"environmentName"`
	Host                 *string `json:"host"`
	HTTPS                *bool   `json:"https"`
	BasicAuthEnabled     *bool   `json:"basicAuthEnabled"`
	BasicAuthUsername    *string `json:"basicAuthUsername"`
	BasicAuthPassword    *string `json:"basicAuthPassword"`
	BasicAuthPasswordSet bool    `json:"basicAuthPasswordSet"`
}

// ServiceEnvironmentConfigStored is the persisted row shape, hash included —
// used by the deploy engine, never serialized to clients.
type ServiceEnvironmentConfigStored = serviceEnvironmentConfigRow

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
	var view ServiceConfigView
	var internalPorts, environmentConfigs sql.NullString
	err := s.db.QueryRowContext(ctx,
		`SELECT id, created_time, namespace, application_name, port, internal_ports, environment_configs
		 FROM application_service_config WHERE namespace = ? AND application_name = ?`,
		namespace, applicationName).
		Scan(&view.ID, &view.CreatedTime, &view.Namespace, &view.ApplicationName, &view.Port,
			&internalPorts, &environmentConfigs)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if err := decodeJSONColumn(internalPorts, &view.InternalPorts); err != nil {
		return nil, err
	}
	var rows []serviceEnvironmentConfigRow
	if err := decodeJSONColumn(environmentConfigs, &rows); err != nil {
		return nil, err
	}
	view.StoredEnvironmentConfigs = rows
	if rows != nil {
		// A stored "[]" renders as [] like the Java converter; only a NULL
		// column stays null.
		view.EnvironmentConfigs = []ServiceEnvironmentConfig{}
	}
	for _, row := range rows {
		view.EnvironmentConfigs = append(view.EnvironmentConfigs, ServiceEnvironmentConfig{
			EnvironmentName:      row.EnvironmentName,
			Host:                 row.Host,
			HTTPS:                row.HTTPS,
			BasicAuthEnabled:     row.BasicAuthEnabled,
			BasicAuthUsername:    row.BasicAuthUsername,
			BasicAuthPasswordSet: row.BasicAuthPasswordHash != nil && *row.BasicAuthPasswordHash != "",
		})
	}
	return &view, nil
}

type ExpertEnvironmentConfig struct {
	EnvironmentName         *string  `json:"environmentName"`
	ServiceAccountName      *string  `json:"serviceAccountName"`
	Priority                *string  `json:"priority"`
	ScheduledRestartEnabled bool     `json:"scheduledRestartEnabled"`
	ScheduledRestartCron    *string  `json:"scheduledRestartCron"`
	NodeNames               []string `json:"nodeNames"`
}

// ExpertConfigView mirrors ApplicationConfigDto.ExpertConfig.
type ExpertConfigView struct {
	ID                 *string                   `json:"id"`
	CreatedTime        *LocalDateTime            `json:"createdTime"`
	Namespace          string                    `json:"namespace"`
	ApplicationName    string                    `json:"applicationName"`
	EnvironmentConfigs []ExpertEnvironmentConfig `json:"environmentConfigs"`
}

// ListAllExpertConfigs backs the scheduled-restart scan.
func (s *Store) ListAllExpertConfigs(ctx context.Context) ([]ExpertConfigView, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, created_time, namespace, application_name, environment_configs
		 FROM application_expert_config`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	configs := []ExpertConfigView{}
	for rows.Next() {
		var view ExpertConfigView
		var environmentConfigs sql.NullString
		if err := rows.Scan(&view.ID, &view.CreatedTime, &view.Namespace, &view.ApplicationName, &environmentConfigs); err != nil {
			return nil, err
		}
		if err := decodeJSONColumn(environmentConfigs, &view.EnvironmentConfigs); err != nil {
			return nil, err
		}
		configs = append(configs, view)
	}
	return configs, rows.Err()
}

func (s *Store) FindExpertConfig(ctx context.Context, namespace, applicationName string) (*ExpertConfigView, error) {
	var view ExpertConfigView
	var environmentConfigs sql.NullString
	err := s.db.QueryRowContext(ctx,
		`SELECT id, created_time, namespace, application_name, environment_configs
		 FROM application_expert_config WHERE namespace = ? AND application_name = ?`,
		namespace, applicationName).
		Scan(&view.ID, &view.CreatedTime, &view.Namespace, &view.ApplicationName, &environmentConfigs)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if err := decodeJSONColumn(environmentConfigs, &view.EnvironmentConfigs); err != nil {
		return nil, err
	}
	return &view, nil
}

// EnvironmentBinding mirrors ApplicationConfigDto.EnvironmentBinding.
type EnvironmentBinding struct {
	ID              string         `json:"id"`
	CreatedTime     *LocalDateTime `json:"createdTime"`
	Namespace       string         `json:"namespace"`
	ApplicationName string         `json:"applicationName"`
	EnvironmentName string         `json:"environmentName"`
}

// ListEnvironmentBindings filters to environments that still exist, like
// getApplicationEnvironments does.
func (s *Store) ListEnvironmentBindings(ctx context.Context, namespace, applicationName string) ([]EnvironmentBinding, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT binding.id, binding.created_time, binding.namespace, binding.application_name, binding.environment_name
		 FROM application_environment binding
		 JOIN environment ON environment.name = binding.environment_name
		 WHERE binding.namespace = ? AND binding.application_name = ?
		 ORDER BY binding.created_time`, namespace, applicationName)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	bindings := []EnvironmentBinding{}
	for rows.Next() {
		var binding EnvironmentBinding
		if err := rows.Scan(&binding.ID, &binding.CreatedTime, &binding.Namespace,
			&binding.ApplicationName, &binding.EnvironmentName); err != nil {
			return nil, err
		}
		bindings = append(bindings, binding)
	}
	return bindings, rows.Err()
}

func (s *Store) FindApplication(ctx context.Context, namespace, name string) (*Application, error) {
	row := s.db.QueryRowContext(ctx,
		"SELECT "+applicationColumns+" FROM application WHERE namespace = ? AND name = ?",
		namespace, name)
	var application Application
	err := row.Scan(&application.ID, &application.CreatedTime, &application.Name,
		&application.Description, &application.Icon, &application.Namespace, &application.Owner)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &application, nil
}

// ListAllRuntimeSpecs backs the resource-alert target collection.
func (s *Store) ListAllRuntimeSpecs(ctx context.Context) ([]RuntimeSpecView, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, created_time, namespace, application_name, environment_configs, health_check
		 FROM application_runtime_spec`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	specs := []RuntimeSpecView{}
	for rows.Next() {
		var view RuntimeSpecView
		var environmentConfigs, healthCheck sql.NullString
		if err := rows.Scan(&view.ID, &view.CreatedTime, &view.Namespace, &view.ApplicationName,
			&environmentConfigs, &healthCheck); err != nil {
			return nil, err
		}
		if err := decodeJSONColumn(environmentConfigs, &view.EnvironmentConfigs); err != nil {
			return nil, err
		}
		specs = append(specs, view)
	}
	return specs, rows.Err()
}
