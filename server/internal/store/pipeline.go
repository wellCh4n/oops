// Pipelines: views, paging, and the optimistic-lock state transitions.
package store

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/wellch4n/oops/server/internal/domain"
	"strings"
)

// pipelineRecord is the GORM model of the pipeline table.
type pipelineRecord struct {
	ID                     string
	CreatedTime            *LocalDateTime
	Namespace              string
	ApplicationName        string
	Status                 *string
	Artifact               *string
	Environment            *string
	PublishType            *string
	PublishConfig          *string `gorm:"column:publish_config"`
	DeployMode             *string
	OperatorID             *string `gorm:"column:operator_id"`
	Message                *string
	TriggerType            *string
	RollbackFromPipelineID *string `gorm:"column:rollback_from_pipeline_id"`
}

func (pipelineRecord) TableName() string { return "pipeline" }

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

func pipelineRecordToView(record *pipelineRecord) PipelineView {
	view := PipelineView{
		ID:                     record.ID,
		CreatedTime:            record.CreatedTime,
		Namespace:              record.Namespace,
		ApplicationName:        record.ApplicationName,
		Name:                   fmt.Sprintf("%s-pipeline-%s", record.ApplicationName, record.ID),
		Status:                 record.Status,
		Artifact:               record.Artifact,
		Environment:            record.Environment,
		PublishType:            record.PublishType,
		DeployMode:             record.DeployMode,
		OperatorID:             record.OperatorID,
		Message:                record.Message,
		TriggerType:            record.TriggerType,
		RollbackFromPipelineID: record.RollbackFromPipelineID,
	}
	if record.PublishConfig != nil && *record.PublishConfig != "" {
		view.PublishConfig = json.RawMessage(*record.PublishConfig)
	}
	return view
}

func pipelineRecordsToViews(records []pipelineRecord) []PipelineView {
	views := make([]PipelineView, 0, len(records))
	for i := range records {
		views = append(views, pipelineRecordToView(&records[i]))
	}
	return views
}

func (s *Store) PagePipelines(ctx context.Context, namespace, applicationName, environment string, page, size int) (int64, []PipelineView, error) {
	query := s.orm.WithContext(ctx).Model(&pipelineRecord{}).
		Where("namespace = ? AND application_name = ?", namespace, applicationName)
	if environment != "" {
		query = query.Where("environment = ?", environment)
	}
	var total int64
	if err := query.Count(&total).Error; err != nil {
		return 0, nil, err
	}
	var records []pipelineRecord
	err := query.Order("created_time DESC").
		Limit(size).Offset((max(page, 1) - 1) * size).
		Find(&records).Error
	return total, pipelineRecordsToViews(records), err
}

func (s *Store) FindPipeline(ctx context.Context, namespace, applicationName, id string) (*PipelineView, error) {
	var record pipelineRecord
	err := s.orm.WithContext(ctx).
		Where("namespace = ? AND application_name = ? AND id = ?", namespace, applicationName, id).
		First(&record).Error
	if err != nil {
		return nil, notFound(err)
	}
	view := pipelineRecordToView(&record)
	return &view, nil
}

// FindLastSuccessfulPipeline backs the redeploy prefill.
func (s *Store) FindLastSuccessfulPipeline(ctx context.Context, namespace, applicationName string) (*PipelineView, error) {
	var record pipelineRecord
	err := s.orm.WithContext(ctx).
		Where("namespace = ? AND application_name = ? AND status = ?", namespace, applicationName, domain.PipelineSucceeded).
		Order("created_time DESC").
		First(&record).Error
	if err != nil {
		return nil, notFound(err)
	}
	view := pipelineRecordToView(&record)
	return &view, nil
}

// QueryPipelines backs POST /api/index/pipelines.
func (s *Store) QueryPipelines(ctx context.Context, namespace, applicationName string) ([]PipelineView, error) {
	query := s.orm.WithContext(ctx).Model(&pipelineRecord{})
	if namespace != "" {
		query = query.Where("namespace = ?", namespace)
	}
	if applicationName != "" {
		query = query.Where("application_name = ?", applicationName)
	}
	var records []pipelineRecord
	err := query.Order("created_time DESC").Find(&records).Error
	return pipelineRecordsToViews(records), err
}

// FindHostConflict mirrors findHostConflictApplication: another application in
// any namespace already serving this exact host.
func (s *Store) FindHostConflict(ctx context.Context, namespace, applicationName, host string) (map[string]string, error) {
	if host == "" {
		return nil, nil
	}
	var records []serviceConfigRecord
	if err := s.orm.WithContext(ctx).
		Where("environment_configs LIKE ? AND NOT (namespace = ? AND application_name = ?)",
			"%\""+host+"\"%", namespace, applicationName).
		Find(&records).Error; err != nil {
		return nil, err
	}
	for i := range records {
		record := &records[i]
		if !record.EnvironmentConfigs.Valid {
			continue
		}
		for _, config := range record.EnvironmentConfigs.Data {
			if config.Host != nil && *config.Host == host {
				environmentName := ""
				if config.Environment != nil {
					environmentName = *config.Environment
				}
				return map[string]string{
					"namespace":       record.Namespace,
					"applicationName": record.ApplicationName,
					"environmentName": environmentName,
				}, nil
			}
		}
	}
	return nil, nil
}

// ActiveDeployment mirrors ActiveDeploymentDto.
type ActiveDeployment struct {
	Namespace       string         `json:"namespace"`
	ApplicationName string         `json:"applicationName"`
	PipelineID      string         `json:"pipelineId"`
	Environment     *string        `json:"environment"`
	Status          string         `json:"status"`
	CreatedTime     *LocalDateTime `json:"createdTime"`
}

// FindActiveDeployments returns in-flight pipelines, newest first. Namespace
// "all" spans every namespace, matching PipelineService.getActiveDeployments.
func (s *Store) FindActiveDeployments(ctx context.Context, namespace string) ([]ActiveDeployment, error) {
	query := s.orm.WithContext(ctx).Model(&pipelineRecord{}).
		Where("status IN ?", domain.ActivePipelineStatuses)
	if !strings.EqualFold(namespace, "all") {
		query = query.Where("namespace = ?", namespace)
	}
	var records []pipelineRecord
	if err := query.Order("created_time DESC").Find(&records).Error; err != nil {
		return nil, err
	}
	deployments := make([]ActiveDeployment, 0, len(records))
	for i := range records {
		record := &records[i]
		status := ""
		if record.Status != nil {
			status = *record.Status
		}
		deployments = append(deployments, ActiveDeployment{
			Namespace:       record.Namespace,
			ApplicationName: record.ApplicationName,
			PipelineID:      record.ID,
			Environment:     record.Environment,
			Status:          status,
			CreatedTime:     record.CreatedTime,
		})
	}
	return deployments, nil
}

// CreatePipeline inserts an INITIALIZED pipeline and returns its id.
func (s *Store) CreatePipeline(ctx context.Context, namespace, applicationName, environment,
	publishType string, publishConfig any, deployMode, operatorID, triggerType, rollbackFromPipelineID string) (string, error) {

	status := domain.PipelineInitialized
	record := pipelineRecord{
		ID:              domain.NewID(),
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
			namespace, applicationName, domain.ActivePipelineStatuses).
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
