package store

import (
	"context"
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

// ActivePipelineStatuses mirrors DeploymentConcurrencyPolicy.
var ActivePipelineStatuses = []string{"RUNNING", "DEPLOYING", "ROLLING_OUT"}

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
