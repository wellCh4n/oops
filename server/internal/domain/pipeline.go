package domain

import (
	"fmt"
	"strings"
)

// PublishConfig is polymorphic on "type":
//
//	{"type":"GIT","repository":"...","branch":"main"}
//	{"type":"ZIP","objectKey":"...","url":null}
type PublishConfig struct {
	Type       ApplicationSourceType
	Repository *string
	Branch     *string
	ObjectKey  *string
	URL        *string
}

type Pipeline struct {
	ID                     string
	CreatedTime            LocalDateTime
	Namespace              string
	ApplicationName        string
	Status                 PipelineStatus
	Artifact               *string
	Environment            string
	PublishType            ApplicationSourceType
	PublishConfig          *PublishConfig
	DeployMode             DeployMode
	OperatorID             *string
	Message                *string
	TriggerType            PipelineTriggerType
	RollbackFromPipelineID *string
}

// Name is the Kubernetes Job name: "<app>-pipeline-<id>".
func (p *Pipeline) Name() string { return fmt.Sprintf("%s-pipeline-%s", p.ApplicationName, p.ID) }

// Finished = SUCCEEDED, ERROR, STOPPED or BUILD_SUCCEEDED.
func (p *Pipeline) Finished() bool {
	switch p.Status {
	case PipelineSucceeded, PipelineError, PipelineStopped, PipelineBuildSucceeded:
		return true
	}
	return false
}

// InitializePipeline mirrors Pipeline.initialize.
func InitializePipeline(namespace, app, environment string, publishType ApplicationSourceType, mode *DeployMode, operatorID string) *Pipeline {
	dm := DeployImmediate
	if mode != nil && *mode != "" {
		dm = *mode
	}
	return &Pipeline{
		Namespace: namespace, ApplicationName: app, Environment: environment,
		PublishType: publishType, DeployMode: dm, OperatorID: StringOrNil(operatorID),
		Status: PipelineInitialized, TriggerType: TriggerRelease,
	}
}

// RollbackPipeline mirrors Pipeline.rollback.
func RollbackPipeline(source *Pipeline, operatorID string) *Pipeline {
	return &Pipeline{
		Namespace: source.Namespace, ApplicationName: source.ApplicationName, Environment: source.Environment,
		Artifact: source.Artifact, PublishType: source.PublishType, PublishConfig: source.PublishConfig,
		DeployMode: DeployImmediate, OperatorID: StringOrNil(operatorID), Status: PipelineInitialized,
		TriggerType: TriggerRollback, RollbackFromPipelineID: Ptr(source.ID),
	}
}

var allowedTransitions = map[PipelineStatus][]PipelineStatus{
	PipelineInitialized:    {PipelineRunning, PipelineDeploying, PipelineError, PipelineStopped},
	PipelineRunning:        {PipelineBuildSucceeded, PipelineDeploying, PipelineError, PipelineStopped},
	PipelineBuildSucceeded: {PipelineDeploying, PipelineStopped},
	PipelineDeploying:      {PipelineRollingOut, PipelineError, PipelineStopped},
	PipelineRollingOut:     {PipelineSucceeded, PipelineError},
}

// EnsureCanTransition mirrors PipelineStateMachine.ensureCanTransition.
func EnsureCanTransition(current, target PipelineStatus) error {
	if current == "" || target == "" {
		return Biz("Pipeline status is required")
	}
	for _, t := range allowedTransitions[current] {
		if t == target {
			return nil
		}
	}
	return Bizf("Illegal pipeline status transition: %s -> %s", current, target)
}

// EnsureManualDeployable requires BUILD_SUCCEEDED.
func EnsureManualDeployable(current PipelineStatus) error {
	if current != PipelineBuildSucceeded {
		return Biz("Pipeline is not in BUILD_SUCCEEDED state")
	}
	return nil
}

// IsTerminalStatus = SUCCEEDED/ERROR/STOPPED.
func IsTerminalStatus(s PipelineStatus) bool {
	return s == PipelineSucceeded || s == PipelineError || s == PipelineStopped
}

// ActivePipelineStatuses are the statuses the duplicate-deploy guard checks.
var ActivePipelineStatuses = []PipelineStatus{PipelineRunning, PipelineDeploying, PipelineRollingOut}

// ErrApplicationBeingDeployed is the concurrency-guard failure.
func ErrApplicationBeingDeployed() error { return Biz("Application is being deployed") }

// EnsureStrategyMatches mirrors DeployStrategyPolicy.ensureStrategyMatches.
func EnsureStrategyMatches(configured, requested ApplicationSourceType) error {
	if configured == "" {
		configured = SourceGit
	}
	if configured != requested {
		return Biz("Deploy strategy does not match application source type")
	}
	return nil
}

// NormalizeGitBranch: blank -> "main".
func NormalizeGitBranch(branch *string) string {
	if isBlankPtr(branch) {
		return "main"
	}
	return strings.TrimSpace(*branch)
}

// ResolveZipPublishConfig mirrors DeployStrategyPolicy.resolveZipPublishConfig.
func ResolveZipPublishConfig(objectKey, url, legacyRepository *string) (*PublishConfig, error) {
	objectKey, url, legacyRepository = TrimToNil(objectKey), TrimToNil(url), TrimToNil(legacyRepository)
	if objectKey != nil && url != nil {
		return nil, Biz("Only one of objectKey and url is allowed for ZIP publish")
	}
	if objectKey == nil && url == nil {
		if legacyRepository == nil {
			return nil, Biz("Either objectKey or url is required for ZIP publish")
		}
		if strings.HasPrefix(*legacyRepository, "http://") || strings.HasPrefix(*legacyRepository, "https://") {
			url = legacyRepository
		} else {
			objectKey = legacyRepository
		}
	}
	return &PublishConfig{Type: SourceZip, ObjectKey: objectKey, URL: url}, nil
}

// PipelineStatusFromString validates a stored status.
func PipelineStatusFromString(s string) PipelineStatus { return PipelineStatus(s) }
