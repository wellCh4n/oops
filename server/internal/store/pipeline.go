package store

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/wellch4n/oops/server/internal/domain"
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
				if config.EnvironmentName != nil {
					environmentName = *config.EnvironmentName
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
