package store

import (
	"context"
	"database/sql"
	"encoding/json"

	"errors"
	"fmt"
	"github.com/wellch4n/oops/server/internal/domain"
	"strings"
)

// ActiveDeployment mirrors ActiveDeploymentDto.
type ActiveDeployment struct {
	Namespace       string         `json:"namespace"`
	ApplicationName string         `json:"applicationName"`
	PipelineID      string         `json:"pipelineId"`
	Environment     *string        `json:"environment"`
	Status          string         `json:"status"`
	CreatedTime     *LocalDateTime `json:"createdTime"`
}

// PipelineView mirrors PipelineDto. Name is computed as
// "{applicationName}-pipeline-{id}" like Pipeline.getName(); publishConfig is
// a JSON blob passed through untouched.
type PipelineView struct {
	ID                     string          `json:"id"`
	CreatedTime            *LocalDateTime  `json:"createdTime"`
	Namespace              string          `json:"namespace"`
	ApplicationName        string          `json:"applicationName"`
	Name                   string          `json:"name"`
	Status                 *string         `json:"status"`
	Artifact               *string         `json:"artifact"`
	Environment            *string         `json:"environment"`
	PublishType            *string         `json:"publishType"`
	PublishConfig          json.RawMessage `json:"publishConfig"`
	DeployMode             *string         `json:"deployMode"`
	OperatorID             *string         `json:"operatorId"`
	OperatorName           *string         `json:"operatorName"`
	Message                *string         `json:"message"`
	TriggerType            *string         `json:"triggerType"`
	RollbackFromPipelineID *string         `json:"rollbackFromPipelineId"`
}

const pipelineColumns = `id, created_time, namespace, application_name, status, artifact,
	environment, publish_type, publish_config, deploy_mode, operator_id, message,
	trigger_type, rollback_from_pipeline_id`

func scanPipeline(scanner interface{ Scan(...any) error }) (*PipelineView, error) {
	var view PipelineView
	var publishConfig sql.NullString
	err := scanner.Scan(&view.ID, &view.CreatedTime, &view.Namespace, &view.ApplicationName,
		&view.Status, &view.Artifact, &view.Environment, &view.PublishType, &publishConfig,
		&view.DeployMode, &view.OperatorID, &view.Message, &view.TriggerType, &view.RollbackFromPipelineID)
	if err != nil {
		return nil, err
	}
	view.Name = fmt.Sprintf("%s-pipeline-%s", view.ApplicationName, view.ID)
	if publishConfig.Valid && publishConfig.String != "" {
		view.PublishConfig = json.RawMessage(publishConfig.String)
	}
	return &view, nil
}

func (s *Store) PagePipelines(ctx context.Context, namespace, applicationName, environment string, page, size int) (int64, []PipelineView, error) {
	where := "namespace = ? AND application_name = ?"
	args := []any{namespace, applicationName}
	if environment != "" {
		where += " AND environment = ?"
		args = append(args, environment)
	}

	var total int64
	if err := s.db.QueryRowContext(ctx,
		"SELECT COUNT(*) FROM pipeline WHERE "+where, args...).Scan(&total); err != nil {
		return 0, nil, err
	}

	offset := (max(page, 1) - 1) * size
	rows, err := s.db.QueryContext(ctx,
		"SELECT "+pipelineColumns+" FROM pipeline WHERE "+where+
			" ORDER BY created_time DESC LIMIT ? OFFSET ?",
		append(args, size, offset)...)
	if err != nil {
		return 0, nil, err
	}
	defer rows.Close()
	pipelines := []PipelineView{}
	for rows.Next() {
		view, err := scanPipeline(rows)
		if err != nil {
			return 0, nil, err
		}
		pipelines = append(pipelines, *view)
	}
	return total, pipelines, rows.Err()
}

func (s *Store) FindPipeline(ctx context.Context, namespace, applicationName, id string) (*PipelineView, error) {
	row := s.db.QueryRowContext(ctx,
		"SELECT "+pipelineColumns+" FROM pipeline WHERE namespace = ? AND application_name = ? AND id = ?",
		namespace, applicationName, id)
	view, err := scanPipeline(row)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return view, err
}

// FindLastSuccessfulPipeline backs the redeploy prefill.
func (s *Store) FindLastSuccessfulPipeline(ctx context.Context, namespace, applicationName string) (*PipelineView, error) {
	row := s.db.QueryRowContext(ctx,
		"SELECT "+pipelineColumns+` FROM pipeline
		 WHERE namespace = ? AND application_name = ? AND status = 'SUCCEEDED'
		 ORDER BY created_time DESC LIMIT 1`,
		namespace, applicationName)
	view, err := scanPipeline(row)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return view, err
}

// QueryPipelines backs POST /api/index/pipelines.
func (s *Store) QueryPipelines(ctx context.Context, namespace, applicationName string) ([]PipelineView, error) {
	where := "1=1"
	args := []any{}
	if namespace != "" {
		where += " AND namespace = ?"
		args = append(args, namespace)
	}
	if applicationName != "" {
		where += " AND application_name = ?"
		args = append(args, applicationName)
	}
	rows, err := s.db.QueryContext(ctx,
		"SELECT "+pipelineColumns+" FROM pipeline WHERE "+where+" ORDER BY created_time DESC", args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	pipelines := []PipelineView{}
	for rows.Next() {
		view, err := scanPipeline(rows)
		if err != nil {
			return nil, err
		}
		pipelines = append(pipelines, *view)
	}
	return pipelines, rows.Err()
}

// FindHostConflict mirrors findHostConflictApplication: another application in
// any namespace already serving this exact host.
func (s *Store) FindHostConflict(ctx context.Context, namespace, applicationName, host string) (map[string]string, error) {
	if host == "" {
		return nil, nil
	}
	rows, err := s.db.QueryContext(ctx,
		`SELECT namespace, application_name, environment_configs FROM application_service_config
		 WHERE environment_configs LIKE ? AND NOT (namespace = ? AND application_name = ?)`,
		"%\""+host+"\"%", namespace, applicationName)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var conflictNamespace, conflictApplication string
		var blob sql.NullString
		if err := rows.Scan(&conflictNamespace, &conflictApplication, &blob); err != nil {
			return nil, err
		}
		var configs []serviceEnvironmentConfigRow
		if err := decodeJSONColumn(blob, &configs); err != nil {
			continue
		}
		for _, config := range configs {
			if config.Host != nil && *config.Host == host {
				environmentName := ""
				if config.EnvironmentName != nil {
					environmentName = *config.EnvironmentName
				}
				return map[string]string{
					"namespace":       conflictNamespace,
					"applicationName": conflictApplication,
					"environmentName": environmentName,
				}, nil
			}
		}
	}
	return nil, rows.Err()
}

// ActivePipelineStatuses mirrors DeploymentConcurrencyPolicy.
var ActivePipelineStatuses = domain.ActivePipelineStatuses

// FindActiveDeployments returns in-flight pipelines, newest first. Namespace
// "all" spans every namespace, matching PipelineService.getActiveDeployments.
func (s *Store) FindActiveDeployments(ctx context.Context, namespace string) ([]ActiveDeployment, error) {
	statusPlaceholders := strings.TrimSuffix(strings.Repeat("?,", len(ActivePipelineStatuses)), ",")
	query := "SELECT namespace, application_name, id, environment, status, created_time FROM pipeline WHERE status IN (" + statusPlaceholders + ")"
	args := make([]any, 0, len(ActivePipelineStatuses)+1)
	for _, status := range ActivePipelineStatuses {
		args = append(args, status)
	}
	if !strings.EqualFold(namespace, "all") {
		query += " AND namespace = ?"
		args = append(args, namespace)
	}
	query += " ORDER BY created_time DESC"

	rows, err := s.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	deployments := []ActiveDeployment{}
	for rows.Next() {
		var deployment ActiveDeployment
		if err := rows.Scan(&deployment.Namespace, &deployment.ApplicationName, &deployment.PipelineID,
			&deployment.Environment, &deployment.Status, &deployment.CreatedTime); err != nil {
			return nil, err
		}
		deployments = append(deployments, deployment)
	}
	return deployments, rows.Err()
}
