package store

import (
	"context"
	"encoding/json"

	"github.com/wellch4n/oops/server/internal/domain"
)

// Pipeline statuses re-exported from domain for existing call sites.
const (
	StatusInitialized    = domain.PipelineInitialized
	StatusRunning        = domain.PipelineRunning
	StatusBuildSucceeded = domain.PipelineBuildSucceeded
	StatusDeploying      = domain.PipelineDeploying
	StatusRollingOut     = domain.PipelineRollingOut
	StatusSucceeded      = domain.PipelineSucceeded
	StatusError          = domain.PipelineError
	StatusStopped        = domain.PipelineStopped
)

// CreatePipeline inserts an INITIALIZED pipeline and returns its id.
func (s *Store) CreatePipeline(ctx context.Context, namespace, applicationName, environment,
	publishType string, publishConfig any, deployMode, operatorID, triggerType, rollbackFromPipelineID string) (string, error) {

	status := StatusInitialized
	record := pipelineRecord{
		ID:              NewNanoID(),
		CreatedTime:     Now(),
		Namespace:       namespace,
		ApplicationName: applicationName,
		Status:          &status,
		Environment:     &environment,
		PublishType:     &publishType,
		DeployMode:      &deployMode,
		OperatorID:      &operatorID,
		TriggerType:     &triggerType,
	}
	if publishConfig != nil {
		encoded, err := json.Marshal(publishConfig)
		if err != nil {
			return "", err
		}
		blob := string(encoded)
		record.PublishConfig = &blob
	}
	if rollbackFromPipelineID != "" {
		record.RollbackFromPipelineID = &rollbackFromPipelineID
	}
	return record.ID, s.orm.WithContext(ctx).Create(&record).Error
}

// UpdatePipelineStatusIfMatch is the optimistic-lock transition: a conditional
// UPDATE whose row count decides who won the race (PipelineRepository semantics).
func (s *Store) UpdatePipelineStatusIfMatch(ctx context.Context, id, expected, target string) (int64, error) {
	result := s.orm.WithContext(ctx).Model(&pipelineRecord{}).
		Where("id = ? AND status = ?", id, expected).
		Update("status", target)
	return result.RowsAffected, result.Error
}

func (s *Store) UpdatePipelineStatusAndMessageIfMatch(ctx context.Context, id, expected, target, message string) (int64, error) {
	result := s.orm.WithContext(ctx).Model(&pipelineRecord{}).
		Where("id = ? AND status = ?", id, expected).
		Updates(map[string]any{"status": target, "message": message})
	return result.RowsAffected, result.Error
}

func (s *Store) UpdatePipelineArtifact(ctx context.Context, id, artifact string) error {
	return s.orm.WithContext(ctx).Model(&pipelineRecord{}).
		Where("id = ?", id).
		Update("artifact", artifact).Error
}

func (s *Store) FindPipelinesByStatus(ctx context.Context, status string) ([]PipelineView, error) {
	var records []pipelineRecord
	err := s.orm.WithContext(ctx).Where("status = ?", status).Find(&records).Error
	return pipelineRecordsToViews(records), err
}

// HasActivePipeline is the duplicate-deploy guard.
func (s *Store) HasActivePipeline(ctx context.Context, namespace, applicationName string) (bool, error) {
	var count int64
	err := s.orm.WithContext(ctx).Model(&pipelineRecord{}).
		Where("namespace = ? AND application_name = ? AND status IN ?",
			namespace, applicationName, ActivePipelineStatuses).
		Count(&count).Error
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
	return s.orm.WithContext(ctx).Where("id = ?", id).Delete(&pipelineRecord{}).Error
}
