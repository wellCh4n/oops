package store

import (
	"context"
	"encoding/json"
)

// Pipeline statuses, mirroring PipelineStatus.
const (
	StatusInitialized    = "INITIALIZED"
	StatusRunning        = "RUNNING"
	StatusBuildSucceeded = "BUILD_SUCCEEDED"
	StatusDeploying      = "DEPLOYING"
	StatusRollingOut     = "ROLLING_OUT"
	StatusSucceeded      = "SUCCEEDED"
	StatusError          = "ERROR"
	StatusStopped        = "STOPPED"
)

// CreatePipeline inserts an INITIALIZED pipeline and returns its id.
func (s *Store) CreatePipeline(ctx context.Context, namespace, applicationName, environment,
	publishType string, publishConfig any, deployMode, operatorID, triggerType, rollbackFromPipelineID string) (string, error) {

	id := NewNanoID()
	publishConfigJSON, err := encodeJSON(publishConfig)
	if err != nil {
		return "", err
	}
	var rollbackFrom any
	if rollbackFromPipelineID != "" {
		rollbackFrom = rollbackFromPipelineID
	}
	_, err = s.db.ExecContext(ctx,
		`INSERT INTO pipeline (id, created_time, namespace, application_name, status, environment,
		        publish_type, publish_config, deploy_mode, operator_id, trigger_type, rollback_from_pipeline_id)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		id, Now(), namespace, applicationName, StatusInitialized, environment,
		publishType, publishConfigJSON, deployMode, operatorID, triggerType, rollbackFrom)
	return id, err
}

// UpdatePipelineStatusIfMatch is the optimistic-lock transition: a conditional
// UPDATE whose row count decides who won the race (PipelineRepository semantics).
func (s *Store) UpdatePipelineStatusIfMatch(ctx context.Context, id, expected, target string) (int64, error) {
	result, err := s.db.ExecContext(ctx,
		"UPDATE pipeline SET status = ? WHERE id = ? AND status = ?", target, id, expected)
	if err != nil {
		return 0, err
	}
	return result.RowsAffected()
}

func (s *Store) UpdatePipelineStatusAndMessageIfMatch(ctx context.Context, id, expected, target, message string) (int64, error) {
	result, err := s.db.ExecContext(ctx,
		"UPDATE pipeline SET status = ?, message = ? WHERE id = ? AND status = ?", target, message, id, expected)
	if err != nil {
		return 0, err
	}
	return result.RowsAffected()
}

func (s *Store) UpdatePipelineArtifact(ctx context.Context, id, artifact string) error {
	_, err := s.db.ExecContext(ctx, "UPDATE pipeline SET artifact = ? WHERE id = ?", artifact, id)
	return err
}

func (s *Store) FindPipelinesByStatus(ctx context.Context, status string) ([]PipelineView, error) {
	rows, err := s.db.QueryContext(ctx,
		"SELECT "+pipelineColumns+" FROM pipeline WHERE status = ?", status)
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

// HasActivePipeline is the duplicate-deploy guard.
func (s *Store) HasActivePipeline(ctx context.Context, namespace, applicationName string) (bool, error) {
	var count int
	err := s.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM pipeline
		 WHERE namespace = ? AND application_name = ? AND status IN ('RUNNING', 'DEPLOYING', 'ROLLING_OUT')`,
		namespace, applicationName).Scan(&count)
	return count > 0, err
}

// GitPublishConfig / ZipPublishConfig mirror the Java publish config JSON,
// which carries a Jackson type discriminator.
type GitPublishConfig struct {
	Type       string `json:"type"`
	Repository string `json:"repository"`
	Branch     string `json:"branch"`
}

type ZipPublishConfig struct {
	Type       string  `json:"type"`
	ObjectKey  *string `json:"objectKey"`
	URL        *string `json:"url"`
	Repository *string `json:"repository"`
}

// DecodePublishConfig picks the variant by the "type" property.
func DecodePublishConfig(raw json.RawMessage) (git *GitPublishConfig, zip *ZipPublishConfig) {
	if len(raw) == 0 {
		return nil, nil
	}
	var head struct {
		Type string `json:"type"`
	}
	if json.Unmarshal(raw, &head) != nil {
		return nil, nil
	}
	switch head.Type {
	case "ZIP":
		var config ZipPublishConfig
		if json.Unmarshal(raw, &config) == nil {
			return nil, &config
		}
	default:
		var config GitPublishConfig
		if json.Unmarshal(raw, &config) == nil {
			return &config, nil
		}
	}
	return nil, nil
}

// DeletePipelineByID removes a pipeline row (verification cleanup only).
func (s *Store) DeletePipelineByID(ctx context.Context, id string) error {
	_, err := s.db.ExecContext(ctx, "DELETE FROM pipeline WHERE id = ?", id)
	return err
}
