package store

import (
	"context"
	"fmt"

	"github.com/wellch4n/oops/server/internal/domain"
)

// PipelineRepository owns the pipeline table: the release history, and the
// conditional updates that make its state machine safe under concurrency.
type PipelineRepository struct {
	store *Store
}

// ---------------------------------------------------------------------------
// row <-> domain

func pipelineFromRow(row pipelineRow) (*domain.Pipeline, error) {
	publishConfig, err := decodeObject[domain.PublishConfig](row.PublishConfig)
	if err != nil {
		return nil, fmt.Errorf("pipeline %s publish_config: %w", row.ID, err)
	}
	return &domain.Pipeline{
		ID:                     row.ID,
		CreatedTime:            row.CreatedTime,
		Namespace:              stringOf(row.Namespace),
		ApplicationName:        stringOf(row.ApplicationName),
		Status:                 domain.PipelineStatus(stringOf(row.Status)),
		Artifact:               ptrOf(row.Artifact),
		Environment:            stringOf(row.Environment),
		PublishType:            domain.ApplicationSourceType(stringOf(row.PublishType)),
		PublishConfig:          publishConfig,
		DeployMode:             domain.DeployMode(stringOf(row.DeployMode)),
		OperatorID:             ptrOf(row.OperatorID),
		Message:                ptrOf(row.Message),
		TriggerType:            domain.PipelineTriggerType(stringOf(row.TriggerType)),
		RollbackFromPipelineID: ptrOf(row.RollbackFromPipelineID),
	}, nil
}

func pipelinesFromRows(rows []pipelineRow) ([]domain.Pipeline, error) {
	result := make([]domain.Pipeline, 0, len(rows))
	for _, row := range rows {
		pipeline, err := pipelineFromRow(row)
		if err != nil {
			return nil, err
		}
		result = append(result, *pipeline)
	}
	return result, nil
}

// statusNames renders a status list for an `IN (?)` parameter.
func statusNames(statuses []domain.PipelineStatus) []string {
	names := make([]string, 0, len(statuses))
	for _, status := range statuses {
		names = append(names, string(status))
	}
	return names
}

// ---------------------------------------------------------------------------
// reads

// FindByID loads a pipeline by primary key; nil when absent.
func (r *PipelineRepository) FindByID(ctx context.Context, id string) (*domain.Pipeline, error) {
	row, err := getOrNil[pipelineRow](ctx, r.store.db, `SELECT * FROM pipeline WHERE id = ? LIMIT 1`, id)
	if err != nil || row == nil {
		return nil, err
	}
	return pipelineFromRow(*row)
}

// Find loads a pipeline scoped to its application; nil when absent.
func (r *PipelineRepository) Find(ctx context.Context, namespace, applicationName, id string) (*domain.Pipeline, error) {
	row, err := getOrNil[pipelineRow](ctx, r.store.db,
		`SELECT * FROM pipeline WHERE namespace = ? AND application_name = ? AND id = ? LIMIT 1`,
		namespace, applicationName, id)
	if err != nil || row == nil {
		return nil, err
	}
	return pipelineFromRow(*row)
}

// FindByApplication returns every pipeline of one application.
func (r *PipelineRepository) FindByApplication(ctx context.Context, namespace, applicationName string) ([]domain.Pipeline, error) {
	rows, err := list[pipelineRow](ctx, r.store.db,
		`SELECT * FROM pipeline WHERE namespace = ? AND application_name = ?`, namespace, applicationName)
	if err != nil {
		return nil, err
	}
	return pipelinesFromRows(rows)
}

// FindByApplicationAndEnvironment narrows FindByApplication to one environment.
func (r *PipelineRepository) FindByApplicationAndEnvironment(ctx context.Context, namespace, applicationName, environment string) ([]domain.Pipeline, error) {
	rows, err := list[pipelineRow](ctx, r.store.db,
		`SELECT * FROM pipeline WHERE namespace = ? AND application_name = ? AND environment = ?`,
		namespace, applicationName, environment)
	if err != nil {
		return nil, err
	}
	return pipelinesFromRows(rows)
}

// FindAllByStatus returns every pipeline in one status — what the scan job asks
// for on each tick.
func (r *PipelineRepository) FindAllByStatus(ctx context.Context, status domain.PipelineStatus) ([]domain.Pipeline, error) {
	rows, err := list[pipelineRow](ctx, r.store.db, `SELECT * FROM pipeline WHERE status = ?`, string(status))
	if err != nil {
		return nil, err
	}
	return pipelinesFromRows(rows)
}

// FindAllByNamespace returns every pipeline of one namespace.
func (r *PipelineRepository) FindAllByNamespace(ctx context.Context, namespace string) ([]domain.Pipeline, error) {
	rows, err := list[pipelineRow](ctx, r.store.db, `SELECT * FROM pipeline WHERE namespace = ?`, namespace)
	if err != nil {
		return nil, err
	}
	return pipelinesFromRows(rows)
}

// FindByStatusIn returns every pipeline in any of the statuses.
func (r *PipelineRepository) FindByStatusIn(ctx context.Context, statuses []domain.PipelineStatus) ([]domain.Pipeline, error) {
	if len(statuses) == 0 {
		return []domain.Pipeline{}, nil
	}
	rows, err := listIn[pipelineRow](ctx, r.store.db,
		`SELECT * FROM pipeline WHERE status IN (?)`, statusNames(statuses))
	if err != nil {
		return nil, err
	}
	return pipelinesFromRows(rows)
}

// FindByNamespaceAndStatusIn narrows FindByStatusIn to one namespace.
func (r *PipelineRepository) FindByNamespaceAndStatusIn(ctx context.Context, namespace string, statuses []domain.PipelineStatus) ([]domain.Pipeline, error) {
	if len(statuses) == 0 {
		return []domain.Pipeline{}, nil
	}
	rows, err := listIn[pipelineRow](ctx, r.store.db,
		`SELECT * FROM pipeline WHERE namespace = ? AND status IN (?)`, namespace, statusNames(statuses))
	if err != nil {
		return nil, err
	}
	return pipelinesFromRows(rows)
}

// FindLatestByStatus returns the newest pipeline of one application in a
// status; nil when there is none. Used to find the artifact a rollback reuses.
func (r *PipelineRepository) FindLatestByStatus(ctx context.Context, namespace, applicationName string, status domain.PipelineStatus) (*domain.Pipeline, error) {
	row, err := getOrNil[pipelineRow](ctx, r.store.db,
		`SELECT * FROM pipeline WHERE namespace = ? AND application_name = ? AND status = ?
ORDER BY created_time DESC LIMIT 1`,
		namespace, applicationName, string(status))
	if err != nil || row == nil {
		return nil, err
	}
	return pipelineFromRow(*row)
}

// ExistsByStatusIn is the duplicate-deploy guard: is one of this application's
// pipelines still in flight?
func (r *PipelineRepository) ExistsByStatusIn(ctx context.Context, namespace, applicationName string, statuses []domain.PipelineStatus) (bool, error) {
	if len(statuses) == 0 {
		return false, nil
	}
	return existsIn(ctx, r.store.db,
		`SELECT 1 FROM pipeline WHERE namespace = ? AND application_name = ? AND status IN (?) LIMIT 1`,
		namespace, applicationName, statusNames(statuses))
}

// pipelinePageFilter is shared by the page and its count so the two cannot
// drift. A namespace of "all" spans every namespace and a blank environment
// does not filter.
const pipelinePageFilter = `
WHERE (? = 'all' OR namespace = ?)
  AND application_name = ?
  AND (? = '' OR environment = ?)`

// FindPage is the paged pipeline list, newest first.
func (r *PipelineRepository) FindPage(ctx context.Context, namespace, applicationName, environment string, page, size int) (Page[domain.Pipeline], error) {
	filterArgs := []any{namespace, namespace, applicationName, environment, environment}
	total, err := count(ctx, r.store.db, `SELECT COUNT(*) FROM pipeline`+pipelinePageFilter, filterArgs...)
	if err != nil {
		return Page[domain.Pipeline]{}, err
	}
	limit, offset := pageWindow(page, size)
	rows, err := list[pipelineRow](ctx, r.store.db,
		`SELECT * FROM pipeline`+pipelinePageFilter+`
ORDER BY created_time DESC
LIMIT ? OFFSET ?`,
		append(filterArgs, limit, offset)...)
	if err != nil {
		return Page[domain.Pipeline]{}, err
	}
	pipelines, err := pipelinesFromRows(rows)
	if err != nil {
		return Page[domain.Pipeline]{}, err
	}
	return newPage(total, pipelines, size), nil
}

// Query is the cross-namespace index search: a blank namespace or application
// name does not filter, and the name matches as a %substring%.
func (r *PipelineRepository) Query(ctx context.Context, namespace, applicationName string) ([]domain.Pipeline, error) {
	pattern := ""
	if applicationName != "" {
		pattern = "%" + applicationName + "%"
	}
	rows, err := list[pipelineRow](ctx, r.store.db,
		`SELECT * FROM pipeline
WHERE (? = '' OR namespace = ?)
  AND (? = '' OR application_name LIKE ?)`,
		namespace, namespace, pattern, pattern)
	if err != nil {
		return nil, err
	}
	return pipelinesFromRows(rows)
}

// ---------------------------------------------------------------------------
// writes

// Save inserts or updates a pipeline, stamping id and created_time on insert.
func (r *PipelineRepository) Save(ctx context.Context, pipeline *domain.Pipeline) (*domain.Pipeline, error) {
	publishConfig, err := encodeObject(pipeline.PublishConfig)
	if err != nil {
		return nil, err
	}
	found := false
	if pipeline.ID != "" {
		if found, err = exists(ctx, r.store.db, `SELECT 1 FROM pipeline WHERE id = ? LIMIT 1`, pipeline.ID); err != nil {
			return nil, err
		}
	}
	if !found {
		pipeline.ID = ensureID(pipeline.ID)
		if pipeline.CreatedTime.IsZero() {
			pipeline.CreatedTime = domain.Now()
		}
		err = exec(ctx, r.store.db,
			`INSERT INTO pipeline
(id, created_time, namespace, application_name, status, artifact, environment, publish_type,
 deploy_mode, operator_id, message, trigger_type, rollback_from_pipeline_id, publish_config)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
			pipeline.ID, pipeline.CreatedTime, pipeline.Namespace, pipeline.ApplicationName,
			string(pipeline.Status), nullString(pipeline.Artifact), pipeline.Environment,
			string(pipeline.PublishType), string(pipeline.DeployMode), nullString(pipeline.OperatorID),
			nullString(pipeline.Message), string(pipeline.TriggerType),
			nullString(pipeline.RollbackFromPipelineID), publishConfig)
	} else {
		_, err = execRows(ctx, r.store.db,
			`UPDATE pipeline
SET created_time = ?, namespace = ?, application_name = ?, status = ?, artifact = ?, environment = ?,
    publish_type = ?, deploy_mode = ?, operator_id = ?, message = ?, trigger_type = ?,
    rollback_from_pipeline_id = ?, publish_config = ?
WHERE id = ?`,
			pipeline.CreatedTime, pipeline.Namespace, pipeline.ApplicationName,
			string(pipeline.Status), nullString(pipeline.Artifact), pipeline.Environment,
			string(pipeline.PublishType), string(pipeline.DeployMode), nullString(pipeline.OperatorID),
			nullString(pipeline.Message), string(pipeline.TriggerType),
			nullString(pipeline.RollbackFromPipelineID), publishConfig, pipeline.ID)
	}
	if err != nil {
		return nil, err
	}
	return pipeline, nil
}

// UpdateStatusIfMatch is the optimistic lock: the WHERE carries the status the
// caller believes the pipeline is in, so the row count says whether this worker
// won the transition. 0 means another one did.
func (r *PipelineRepository) UpdateStatusIfMatch(ctx context.Context, id string, expected, target domain.PipelineStatus) (int64, error) {
	return execRows(ctx, r.store.db,
		`UPDATE pipeline SET status = ? WHERE id = ? AND status = ?`,
		string(target), id, string(expected))
}

// UpdateStatusAndMessageIfMatch is UpdateStatusIfMatch carrying a message.
func (r *PipelineRepository) UpdateStatusAndMessageIfMatch(ctx context.Context, id string, expected, target domain.PipelineStatus, message *string) (int64, error) {
	return execRows(ctx, r.store.db,
		`UPDATE pipeline SET status = ?, message = ? WHERE id = ? AND status = ?`,
		string(target), nullString(message), id, string(expected))
}

// MigrateNamespace moves an application's pipelines to another namespace.
func (r *PipelineRepository) MigrateNamespace(ctx context.Context, sourceNamespace, targetNamespace, applicationName string) (int64, error) {
	return execRows(ctx, r.store.db,
		`UPDATE pipeline SET namespace = ? WHERE namespace = ? AND application_name = ?`,
		targetNamespace, sourceNamespace, applicationName)
}
