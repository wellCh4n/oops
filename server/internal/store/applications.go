package store

import (
	"context"
	"fmt"

	"github.com/wellch4n/oops/server/internal/domain"
)

// ApplicationRepository owns the application aggregate: the root row, its four
// singleton config children, its environment bindings and its collaborators.
//
// Reads use `SELECT *` against the row structs in rows.go. sqlx refuses a column
// it has no field for, so a schema change that outgrows a row struct fails
// loudly on the first query rather than silently dropping data.
type ApplicationRepository struct {
	store *Store
}

// ---------------------------------------------------------------------------
// row <-> domain

func applicationFromRow(row applicationRow) domain.Application {
	return domain.Application{
		ID:          row.ID,
		CreatedTime: row.CreatedTime,
		Name:        stringOf(row.Name),
		Description: ptrOf(row.Description),
		Icon:        ptrOf(row.Icon),
		Namespace:   stringOf(row.Namespace),
		Owner:       ptrOf(row.Owner),
	}
}

func applicationsFromRows(rows []applicationRow) []domain.Application {
	result := make([]domain.Application, 0, len(rows))
	for _, row := range rows {
		result = append(result, applicationFromRow(row))
	}
	return result
}

func buildConfigFromRow(row buildConfigRow) (*domain.ApplicationBuildConfig, error) {
	sourceConfig, err := decodeObject[domain.SourceConfig](row.SourceConfig)
	if err != nil {
		return nil, fmt.Errorf("build config %s source_config: %w", row.ID, err)
	}
	dockerFileConfig, err := decodeObject[domain.DockerFileConfig](row.DockerFileConfig)
	if err != nil {
		return nil, fmt.Errorf("build config %s docker_file_config: %w", row.ID, err)
	}
	environmentConfigs, err := decodeSlice[domain.BuildEnvironmentConfig](row.EnvironmentConfigs)
	if err != nil {
		return nil, fmt.Errorf("build config %s environment_configs: %w", row.ID, err)
	}
	return &domain.ApplicationBuildConfig{
		ID:                 row.ID,
		CreatedTime:        row.CreatedTime,
		Namespace:          stringOf(row.Namespace),
		ApplicationName:    stringOf(row.ApplicationName),
		SourceType:         enumPtrOf[domain.ApplicationSourceType](row.SourceType),
		SourceConfig:       sourceConfig,
		DockerFileConfig:   dockerFileConfig,
		BuildImage:         ptrOf(row.BuildImage),
		EnvironmentConfigs: environmentConfigs,
	}, nil
}

func runtimeSpecFromRow(row runtimeSpecRow) (*domain.ApplicationRuntimeSpec, error) {
	environmentConfigs, err := decodeSlice[domain.RuntimeEnvironmentConfig](row.EnvironmentConfigs)
	if err != nil {
		return nil, fmt.Errorf("runtime spec %s environment_configs: %w", row.ID, err)
	}
	healthCheck, err := decodeHealthCheck(row.HealthCheck)
	if err != nil {
		return nil, fmt.Errorf("runtime spec %s health_check: %w", row.ID, err)
	}
	return &domain.ApplicationRuntimeSpec{
		ID:                 row.ID,
		CreatedTime:        row.CreatedTime,
		Namespace:          stringOf(row.Namespace),
		ApplicationName:    stringOf(row.ApplicationName),
		EnvironmentConfigs: environmentConfigs,
		HealthCheck:        healthCheck,
	}, nil
}

func serviceConfigFromRow(row serviceConfigRow) (*domain.ApplicationServiceConfig, error) {
	environmentConfigs, err := decodeServiceEnvironmentConfigs(row.EnvironmentConfigs)
	if err != nil {
		return nil, fmt.Errorf("service config %s environment_configs: %w", row.ID, err)
	}
	internalPorts, err := decodeSlice[int](row.InternalPorts)
	if err != nil {
		return nil, fmt.Errorf("service config %s internal_ports: %w", row.ID, err)
	}
	return &domain.ApplicationServiceConfig{
		ID:                 row.ID,
		CreatedTime:        row.CreatedTime,
		Namespace:          stringOf(row.Namespace),
		ApplicationName:    stringOf(row.ApplicationName),
		Port:               intPtrOf(row.Port),
		InternalPorts:      internalPorts,
		EnvironmentConfigs: environmentConfigs,
	}, nil
}

func expertConfigFromRow(row expertConfigRow) (*domain.ApplicationExpertConfig, error) {
	environmentConfigs, err := decodeSlice[domain.ExpertEnvironmentConfig](row.EnvironmentConfigs)
	if err != nil {
		return nil, fmt.Errorf("expert config %s environment_configs: %w", row.ID, err)
	}
	return &domain.ApplicationExpertConfig{
		ID:                 row.ID,
		CreatedTime:        row.CreatedTime,
		Namespace:          stringOf(row.Namespace),
		ApplicationName:    stringOf(row.ApplicationName),
		EnvironmentConfigs: environmentConfigs,
	}, nil
}

// ---------------------------------------------------------------------------
// reads

// FindRow loads only the application row (no children). nil when absent.
func (r *ApplicationRepository) FindRow(ctx context.Context, namespace, name string) (*domain.Application, error) {
	row, err := getOrNil[applicationRow](ctx, r.store.db,
		`SELECT * FROM application WHERE namespace = ? AND name = ? LIMIT 1`, namespace, name)
	if err != nil || row == nil {
		return nil, err
	}
	application := applicationFromRow(*row)
	return &application, nil
}

// FindAggregate loads the application and hydrates every child. nil when the
// root row does not exist; an absent child stays nil.
func (r *ApplicationRepository) FindAggregate(ctx context.Context, namespace, name string) (*domain.Application, error) {
	return findAggregate(ctx, r.store.db, namespace, name)
}

// findAggregate takes a queryer so the write path can re-read inside its own
// transaction and see what it just wrote.
func findAggregate(ctx context.Context, q queryer, namespace, name string) (*domain.Application, error) {
	root, err := getOrNil[applicationRow](ctx, q,
		`SELECT * FROM application WHERE namespace = ? AND name = ? LIMIT 1`, namespace, name)
	if err != nil || root == nil {
		return nil, err
	}
	application := applicationFromRow(*root)

	buildRow, err := getOrNil[buildConfigRow](ctx, q,
		`SELECT * FROM application_build_config WHERE namespace = ? AND application_name = ? LIMIT 1`, namespace, name)
	if err != nil {
		return nil, err
	}
	if buildRow != nil {
		if application.BuildConfig, err = buildConfigFromRow(*buildRow); err != nil {
			return nil, err
		}
	}

	runtimeRow, err := getOrNil[runtimeSpecRow](ctx, q,
		`SELECT * FROM application_runtime_spec WHERE namespace = ? AND application_name = ? LIMIT 1`, namespace, name)
	if err != nil {
		return nil, err
	}
	if runtimeRow != nil {
		if application.RuntimeSpec, err = runtimeSpecFromRow(*runtimeRow); err != nil {
			return nil, err
		}
	}

	serviceRow, err := getOrNil[serviceConfigRow](ctx, q,
		`SELECT * FROM application_service_config WHERE namespace = ? AND application_name = ? LIMIT 1`, namespace, name)
	if err != nil {
		return nil, err
	}
	if serviceRow != nil {
		if application.ServiceConfig, err = serviceConfigFromRow(*serviceRow); err != nil {
			return nil, err
		}
	}

	expertRow, err := getOrNil[expertConfigRow](ctx, q,
		`SELECT * FROM application_expert_config WHERE namespace = ? AND application_name = ? LIMIT 1`, namespace, name)
	if err != nil {
		return nil, err
	}
	if expertRow != nil {
		if application.ExpertConfig, err = expertConfigFromRow(*expertRow); err != nil {
			return nil, err
		}
	}

	environmentRows, err := list[applicationEnvironmentRow](ctx, q,
		`SELECT * FROM application_environment WHERE namespace = ? AND application_name = ?`, namespace, name)
	if err != nil {
		return nil, err
	}
	application.Environments = make([]domain.ApplicationEnvironment, 0, len(environmentRows))
	for _, row := range environmentRows {
		application.Environments = append(application.Environments, domain.ApplicationEnvironment{
			ID: row.ID, CreatedTime: row.CreatedTime, Namespace: stringOf(row.Namespace),
			ApplicationName: stringOf(row.ApplicationName), Environment: stringOf(row.Environment),
		})
	}

	collaboratorRows, err := list[collaboratorRow](ctx, q,
		`SELECT * FROM application_collaborator WHERE namespace = ? AND application_name = ?`, namespace, name)
	if err != nil {
		return nil, err
	}
	application.Collaborators = make([]domain.ApplicationCollaborator, 0, len(collaboratorRows))
	for _, row := range collaboratorRows {
		application.Collaborators = append(application.Collaborators, domain.ApplicationCollaborator{
			ID: row.ID, CreatedTime: row.CreatedTime, Namespace: stringOf(row.Namespace),
			ApplicationName: stringOf(row.ApplicationName), UserID: stringOf(row.UserID),
		})
	}
	return &application, nil
}

// applicationPageFilter is the WHERE the list and its count share, so the two
// can never drift into disagreeing about what they are counting.
const applicationPageFilter = `
WHERE (? = 'all' OR a.namespace = ?)
  AND (LOWER(a.name) LIKE LOWER(?) OR LOWER(a.description) LIKE LOWER(?))
  AND (? IS NULL OR a.owner = ?)`

// FindPageOrderedByOwner is the application list: namespace "all" means every
// namespace, keyword matches name or description case-insensitively, ownerID
// (nil = no filter) restricts to one owner.
//
// The order puts what the caller was last working on at the top: applications
// they most recently deployed first, then the ones they own, then newest. Rows
// carry no children.
func (r *ApplicationRepository) FindPageOrderedByOwner(ctx context.Context, namespace, keyword, currentUserID string, ownerID *string, page, size int) (Page[domain.Application], error) {
	limit, offset := pageWindow(page, size)
	pattern := "%" + keyword + "%"
	owner := nullString(ownerID)
	filterArgs := []any{namespace, namespace, pattern, pattern, owner, owner}

	rows, err := list[applicationRow](ctx, r.store.db,
		`SELECT a.* FROM application a`+applicationPageFilter+`
ORDER BY (SELECT MAX(p.created_time) FROM pipeline p
          WHERE p.namespace = a.namespace AND p.application_name = a.name AND p.operator_id = ?) DESC,
         CASE WHEN a.owner = ? THEN 0 ELSE 1 END,
         a.created_time DESC
LIMIT ? OFFSET ?`,
		append(filterArgs, currentUserID, currentUserID, limit, offset)...)
	if err != nil {
		return Page[domain.Application]{}, err
	}
	total, err := count(ctx, r.store.db, `SELECT COUNT(*) FROM application a`+applicationPageFilter, filterArgs...)
	if err != nil {
		return Page[domain.Application]{}, err
	}
	return newPage(total, applicationsFromRows(rows), size), nil
}

// FindByNameContainingIgnoreCase searches every namespace, unpaged.
func (r *ApplicationRepository) FindByNameContainingIgnoreCase(ctx context.Context, keyword string) ([]domain.Application, error) {
	rows, err := list[applicationRow](ctx, r.store.db,
		`SELECT * FROM application WHERE UPPER(name) LIKE UPPER(?)`, "%"+keyword+"%")
	if err != nil {
		return nil, err
	}
	return applicationsFromRows(rows), nil
}

// Query filters by name LIKE %name% and namespace equality, each skipped when
// blank — the POST /api/index/applications criteria.
func (r *ApplicationRepository) Query(ctx context.Context, namespace, name string) ([]domain.Application, error) {
	namePattern := ""
	if name != "" {
		namePattern = "%" + name + "%"
	}
	rows, err := list[applicationRow](ctx, r.store.db,
		`SELECT * FROM application
WHERE (? = '' OR name LIKE ?)
  AND (? = '' OR namespace = ?)`,
		namePattern, namePattern, namespace, namespace)
	if err != nil {
		return nil, err
	}
	return applicationsFromRows(rows), nil
}

// FindBuildConfigs loads the build configs of several applications in one
// namespace.
func (r *ApplicationRepository) FindBuildConfigs(ctx context.Context, namespace string, names []string) ([]domain.ApplicationBuildConfig, error) {
	if len(names) == 0 {
		return []domain.ApplicationBuildConfig{}, nil
	}
	rows, err := listIn[buildConfigRow](ctx, r.store.db,
		`SELECT * FROM application_build_config WHERE namespace = ? AND application_name IN (?)`, namespace, names)
	if err != nil {
		return nil, err
	}
	return buildConfigsFromRows(rows)
}

// FindBuildConfigsIn is FindBuildConfigs across several namespaces, for the
// cross-namespace listings.
func (r *ApplicationRepository) FindBuildConfigsIn(ctx context.Context, namespaces, names []string) ([]domain.ApplicationBuildConfig, error) {
	if len(names) == 0 || len(namespaces) == 0 {
		return []domain.ApplicationBuildConfig{}, nil
	}
	rows, err := listIn[buildConfigRow](ctx, r.store.db,
		`SELECT * FROM application_build_config WHERE namespace IN (?) AND application_name IN (?)`, namespaces, names)
	if err != nil {
		return nil, err
	}
	return buildConfigsFromRows(rows)
}

func buildConfigsFromRows(rows []buildConfigRow) ([]domain.ApplicationBuildConfig, error) {
	result := make([]domain.ApplicationBuildConfig, 0, len(rows))
	for _, row := range rows {
		config, err := buildConfigFromRow(row)
		if err != nil {
			return nil, err
		}
		result = append(result, *config)
	}
	return result, nil
}

// FindServiceConfigsByHostLikeExcludingSelf is the host-conflict prefilter: it
// matches the quoted host inside the JSON blob, so callers pass `"host"` and the
// exact comparison still happens in Go.
func (r *ApplicationRepository) FindServiceConfigsByHostLikeExcludingSelf(ctx context.Context, hostPattern, namespace, applicationName string) ([]domain.ApplicationServiceConfig, error) {
	rows, err := list[serviceConfigRow](ctx, r.store.db,
		`SELECT * FROM application_service_config
WHERE environment_configs LIKE ?
  AND (namespace <> ? OR application_name <> ?)`,
		"%"+hostPattern+"%", namespace, applicationName)
	if err != nil {
		return nil, err
	}
	return serviceConfigsFromRows(rows)
}

// FindAllServiceConfigs returns every service config, for the domain-rebinding
// check that has to look at every host in the installation.
func (r *ApplicationRepository) FindAllServiceConfigs(ctx context.Context) ([]domain.ApplicationServiceConfig, error) {
	rows, err := list[serviceConfigRow](ctx, r.store.db, `SELECT * FROM application_service_config`)
	if err != nil {
		return nil, err
	}
	return serviceConfigsFromRows(rows)
}

func serviceConfigsFromRows(rows []serviceConfigRow) ([]domain.ApplicationServiceConfig, error) {
	result := make([]domain.ApplicationServiceConfig, 0, len(rows))
	for _, row := range rows {
		config, err := serviceConfigFromRow(row)
		if err != nil {
			return nil, err
		}
		result = append(result, *config)
	}
	return result, nil
}

// FindAllRuntimeSpecs returns every runtime spec (the resource alert scan reads
// the whole table in one query rather than one per application).
func (r *ApplicationRepository) FindAllRuntimeSpecs(ctx context.Context) ([]domain.ApplicationRuntimeSpec, error) {
	rows, err := list[runtimeSpecRow](ctx, r.store.db, `SELECT * FROM application_runtime_spec`)
	if err != nil {
		return nil, err
	}
	result := make([]domain.ApplicationRuntimeSpec, 0, len(rows))
	for _, row := range rows {
		spec, err := runtimeSpecFromRow(row)
		if err != nil {
			return nil, err
		}
		result = append(result, *spec)
	}
	return result, nil
}

// FindAllExpertConfigs returns every expert config (the scheduled-restart scan).
func (r *ApplicationRepository) FindAllExpertConfigs(ctx context.Context) ([]domain.ApplicationExpertConfig, error) {
	rows, err := list[expertConfigRow](ctx, r.store.db, `SELECT * FROM application_expert_config`)
	if err != nil {
		return nil, err
	}
	result := make([]domain.ApplicationExpertConfig, 0, len(rows))
	for _, row := range rows {
		config, err := expertConfigFromRow(row)
		if err != nil {
			return nil, err
		}
		result = append(result, *config)
	}
	return result, nil
}

// ---------------------------------------------------------------------------
// writes

// SaveApplicationRow inserts or updates only the application row. A duplicate
// name surfaces as ErrDuplicate.
func (r *ApplicationRepository) SaveApplicationRow(ctx context.Context, application *domain.Application) (*domain.Application, error) {
	saved := *application
	if err := saveApplicationRow(ctx, r.store.db, &saved); err != nil {
		return nil, err
	}
	return &saved, nil
}

func saveApplicationRow(ctx context.Context, q queryer, application *domain.Application) error {
	found := false
	if application.ID != "" {
		var err error
		if found, err = exists(ctx, q, `SELECT 1 FROM application WHERE id = ? LIMIT 1`, application.ID); err != nil {
			return err
		}
	}
	if found {
		_, err := execRows(ctx, q,
			`UPDATE application SET created_time = ?, description = ?, name = ?, namespace = ?, owner = ?, icon = ?
WHERE id = ?`,
			application.CreatedTime, nullString(application.Description), application.Name,
			application.Namespace, nullString(application.Owner), nullString(application.Icon), application.ID)
		return err
	}
	application.ID = ensureID(application.ID)
	application.CreatedTime = domain.Now()
	return exec(ctx, q,
		`INSERT INTO application (id, created_time, description, name, namespace, owner, icon)
VALUES (?, ?, ?, ?, ?, ?, ?)`,
		application.ID, application.CreatedTime, nullString(application.Description), application.Name,
		application.Namespace, nullString(application.Owner), nullString(application.Icon))
}

// SaveAggregate writes the root and every non-nil child in one transaction:
// singleton children are upserted by id; Environments and Collaborators (when
// non-nil) are deleted and re-inserted. A nil child is left untouched, which is
// what lets each endpoint write only the part of the aggregate it owns.
// Returns the re-read aggregate.
func (r *ApplicationRepository) SaveAggregate(ctx context.Context, application *domain.Application) (*domain.Application, error) {
	var result *domain.Application
	err := r.store.withTx(ctx, func(tx queryer) error {
		root := *application
		if err := saveApplicationRow(ctx, tx, &root); err != nil {
			return err
		}
		namespace, name := root.Namespace, root.Name

		if application.BuildConfig != nil {
			config := *application.BuildConfig
			config.Namespace, config.ApplicationName = namespace, name
			if err := saveBuildConfig(ctx, tx, &config); err != nil {
				return err
			}
		}
		if application.RuntimeSpec != nil {
			spec := *application.RuntimeSpec
			spec.Namespace, spec.ApplicationName = namespace, name
			if err := saveRuntimeSpec(ctx, tx, &spec); err != nil {
				return err
			}
		}
		if application.ServiceConfig != nil {
			config := *application.ServiceConfig
			config.Namespace, config.ApplicationName = namespace, name
			if err := saveServiceConfig(ctx, tx, &config); err != nil {
				return err
			}
		}
		if application.ExpertConfig != nil {
			config := *application.ExpertConfig
			config.Namespace, config.ApplicationName = namespace, name
			if err := saveExpertConfig(ctx, tx, &config); err != nil {
				return err
			}
		}
		if application.Environments != nil {
			if _, err := execRows(ctx, tx,
				`DELETE FROM application_environment WHERE namespace = ? AND application_name = ?`, namespace, name); err != nil {
				return err
			}
			for _, environment := range application.Environments {
				if err := exec(ctx, tx,
					`INSERT INTO application_environment (id, created_time, application_name, environment, namespace)
VALUES (?, ?, ?, ?, ?)`,
					ensureID(environment.ID), domain.Now(), name, environment.Environment, namespace); err != nil {
					return err
				}
			}
		}
		if application.Collaborators != nil {
			if _, err := execRows(ctx, tx,
				`DELETE FROM application_collaborator WHERE namespace = ? AND application_name = ?`, namespace, name); err != nil {
				return err
			}
			for _, collaborator := range application.Collaborators {
				// Always a fresh id: the rows were just deleted, so carrying the
				// old identity over would mean nothing to anyone.
				if err := exec(ctx, tx,
					`INSERT INTO application_collaborator (id, created_time, namespace, application_name, user_id)
VALUES (?, ?, ?, ?, ?)`,
					domain.NewID(), domain.Now(), namespace, name, collaborator.UserID); err != nil {
					return err
				}
			}
		}
		reloaded, err := findAggregate(ctx, tx, namespace, name)
		if err != nil {
			return err
		}
		result = reloaded
		return nil
	})
	if err != nil {
		return nil, err
	}
	return result, nil
}

func saveBuildConfig(ctx context.Context, q queryer, config *domain.ApplicationBuildConfig) error {
	sourceConfig, err := encodeObject(config.SourceConfig)
	if err != nil {
		return err
	}
	dockerFileConfig, err := encodeObject(config.DockerFileConfig)
	if err != nil {
		return err
	}
	environmentConfigs, err := encodeSlice(config.EnvironmentConfigs)
	if err != nil {
		return err
	}
	found := false
	if config.ID != "" {
		if found, err = exists(ctx, q, `SELECT 1 FROM application_build_config WHERE id = ? LIMIT 1`, config.ID); err != nil {
			return err
		}
	}
	if found {
		_, err := execRows(ctx, q,
			`UPDATE application_build_config
SET created_time = ?, application_name = ?, build_image = ?, environment_configs = ?,
    namespace = ?, source_type = ?, docker_file_config = ?, source_config = ?
WHERE id = ?`,
			config.CreatedTime, config.ApplicationName, nullString(config.BuildImage), environmentConfigs,
			config.Namespace, enumString(config.SourceType), dockerFileConfig, sourceConfig, config.ID)
		return err
	}
	config.ID = ensureID(config.ID)
	config.CreatedTime = domain.Now()
	return exec(ctx, q,
		`INSERT INTO application_build_config
(id, created_time, application_name, build_image, environment_configs, namespace, source_type, docker_file_config, source_config)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		config.ID, config.CreatedTime, config.ApplicationName, nullString(config.BuildImage), environmentConfigs,
		config.Namespace, enumString(config.SourceType), dockerFileConfig, sourceConfig)
}

func saveRuntimeSpec(ctx context.Context, q queryer, spec *domain.ApplicationRuntimeSpec) error {
	environmentConfigs, err := encodeSlice(spec.EnvironmentConfigs)
	if err != nil {
		return err
	}
	healthCheck, err := encodeObject(spec.HealthCheck)
	if err != nil {
		return err
	}
	found := false
	if spec.ID != "" {
		if found, err = exists(ctx, q, `SELECT 1 FROM application_runtime_spec WHERE id = ? LIMIT 1`, spec.ID); err != nil {
			return err
		}
	}
	if found {
		_, err := execRows(ctx, q,
			`UPDATE application_runtime_spec
SET created_time = ?, application_name = ?, environment_configs = ?, namespace = ?, health_check = ?
WHERE id = ?`,
			spec.CreatedTime, spec.ApplicationName, environmentConfigs, spec.Namespace, healthCheck, spec.ID)
		return err
	}
	spec.ID = ensureID(spec.ID)
	spec.CreatedTime = domain.Now()
	return exec(ctx, q,
		`INSERT INTO application_runtime_spec
(id, created_time, application_name, environment_configs, namespace, health_check)
VALUES (?, ?, ?, ?, ?, ?)`,
		spec.ID, spec.CreatedTime, spec.ApplicationName, environmentConfigs, spec.Namespace, healthCheck)
}

func saveServiceConfig(ctx context.Context, q queryer, config *domain.ApplicationServiceConfig) error {
	environmentConfigs, err := encodeSlice(config.EnvironmentConfigs)
	if err != nil {
		return err
	}
	internalPorts, err := encodeSlice(config.InternalPorts)
	if err != nil {
		return err
	}
	found := false
	if config.ID != "" {
		if found, err = exists(ctx, q, `SELECT 1 FROM application_service_config WHERE id = ? LIMIT 1`, config.ID); err != nil {
			return err
		}
	}
	if found {
		_, err := execRows(ctx, q,
			`UPDATE application_service_config
SET created_time = ?, application_name = ?, environment_configs = ?, namespace = ?, port = ?, internal_ports = ?
WHERE id = ?`,
			config.CreatedTime, config.ApplicationName, environmentConfigs, config.Namespace,
			nullInt(config.Port), internalPorts, config.ID)
		return err
	}
	config.ID = ensureID(config.ID)
	config.CreatedTime = domain.Now()
	return exec(ctx, q,
		`INSERT INTO application_service_config
(id, created_time, application_name, environment_configs, namespace, port, internal_ports)
VALUES (?, ?, ?, ?, ?, ?, ?)`,
		config.ID, config.CreatedTime, config.ApplicationName, environmentConfigs, config.Namespace,
		nullInt(config.Port), internalPorts)
}

func saveExpertConfig(ctx context.Context, q queryer, config *domain.ApplicationExpertConfig) error {
	environmentConfigs, err := encodeSlice(config.EnvironmentConfigs)
	if err != nil {
		return err
	}
	found := false
	if config.ID != "" {
		if found, err = exists(ctx, q, `SELECT 1 FROM application_expert_config WHERE id = ? LIMIT 1`, config.ID); err != nil {
			return err
		}
	}
	if found {
		_, err := execRows(ctx, q,
			`UPDATE application_expert_config
SET created_time = ?, namespace = ?, application_name = ?, environment_configs = ?
WHERE id = ?`,
			config.CreatedTime, config.Namespace, config.ApplicationName, environmentConfigs, config.ID)
		return err
	}
	config.ID = ensureID(config.ID)
	config.CreatedTime = domain.Now()
	return exec(ctx, q,
		`INSERT INTO application_expert_config (id, created_time, namespace, application_name, environment_configs)
VALUES (?, ?, ?, ?, ?)`,
		config.ID, config.CreatedTime, config.Namespace, config.ApplicationName, environmentConfigs)
}

// applicationChildTables are every table keyed by (namespace, application_name)
// that belongs to the application's lifetime. Pipelines are deliberately absent:
// they are history and outlive the application.
var applicationChildTables = []string{
	"application_environment",
	"application_build_config",
	"application_runtime_spec",
	"application_service_config",
	"application_expert_config",
	"application_collaborator",
	"application_alert_state",
}

// DeleteAggregate removes every child row and finally the application row, in
// one transaction.
func (r *ApplicationRepository) DeleteAggregate(ctx context.Context, namespace, name string) error {
	return r.store.withTx(ctx, func(tx queryer) error {
		for _, table := range applicationChildTables {
			if _, err := execRows(ctx, tx,
				`DELETE FROM `+table+` WHERE namespace = ? AND application_name = ?`, namespace, name); err != nil {
				return err
			}
		}
		_, err := execRows(ctx, tx, `DELETE FROM application WHERE namespace = ? AND name = ?`, namespace, name)
		return err
	})
}

// MigrateNamespace moves the application and its children to another namespace
// in one transaction. Pipelines move separately, through
// PipelineRepository.MigrateNamespace.
func (r *ApplicationRepository) MigrateNamespace(ctx context.Context, sourceNamespace, targetNamespace, name string) error {
	return r.store.withTx(ctx, func(tx queryer) error {
		for _, table := range applicationChildTables {
			if _, err := execRows(ctx, tx,
				`UPDATE `+table+` SET namespace = ? WHERE namespace = ? AND application_name = ?`,
				targetNamespace, sourceNamespace, name); err != nil {
				return err
			}
		}
		_, err := execRows(ctx, tx,
			`UPDATE application SET namespace = ? WHERE namespace = ? AND name = ?`,
			targetNamespace, sourceNamespace, name)
		return err
	})
}
