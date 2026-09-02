package service

import (
	"context"
	"strings"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/k8s"
	"github.com/wellch4n/oops/server/internal/objectstorage"
)

// DeploymentService starts releases: it creates the pipeline row and submits the
// build Job. Everything after the Job is created belongs to the scan job and to
// PipelineService.
type DeploymentService struct {
	services *Services
}

// DeployStrategy is the polymorphic half of a deploy request. GIT carries a
// branch, ZIP carries whichever of objectKey/url the uploader produced.
type DeployStrategy struct {
	Type       domain.ApplicationSourceType `json:"type"`
	Branch     *string                      `json:"branch"`
	ObjectKey  *string                      `json:"objectKey"`
	URL        *string                      `json:"url"`
	Repository *string                      `json:"repository"`
}

// DeployCommand is the deploy request body.
type DeployCommand struct {
	Environment string             `json:"environment"`
	DeployMode  *domain.DeployMode `json:"deployMode"`
	Strategy    *DeployStrategy    `json:"strategy"`
}

// Deploy creates the pipeline and submits its build, returning the pipeline id.
func (s *DeploymentService) Deploy(ctx context.Context, namespace, applicationName string, request DeployCommand, operatorUserID string) (string, error) {
	if request.Strategy == nil {
		return "", domain.Biz("Deploy strategy is required")
	}
	application, err := s.services.Store.Applications().FindAggregate(ctx, namespace, applicationName)
	if err != nil {
		return "", err
	}
	if application == nil {
		return "", domain.Biz("Application not found")
	}
	operator, err := s.services.operator(ctx, operatorUserID)
	if err != nil {
		return "", err
	}
	if err := domain.EnsureCanOperate(application, operator); err != nil {
		return "", err
	}
	if err := s.services.Pipelines.ensureNoActivePipeline(ctx, namespace, applicationName); err != nil {
		return "", err
	}
	environment, err := s.services.environmentByName(ctx, request.Environment)
	if err != nil {
		return "", err
	}
	// The application's configured source type and the requested one must agree:
	// deploying a ZIP to a GIT application would build from the wrong place.
	if err := domain.EnsureStrategyMatches(application.SourceType(), request.Strategy.Type); err != nil {
		return "", err
	}

	pipeline := domain.InitializePipeline(namespace, application.Name, environment.Name,
		request.Strategy.Type, request.DeployMode, operatorUserID)
	if err := s.applyStrategy(pipeline, *request.Strategy, application.BuildConfig); err != nil {
		return "", err
	}
	pipeline, err = s.services.Store.Pipelines().Save(ctx, pipeline)
	if err != nil {
		return "", err
	}
	s.services.Pipelines.Notify(ctx, pipeline, "发布流程已经启动，正在构建镜像。")

	artifact, err := s.submitBuild(ctx, pipeline, application, environment)
	if err != nil {
		return "", err
	}
	pipeline.Artifact = &artifact
	pipeline.Status = domain.PipelineRunning
	if _, err := s.services.Store.Pipelines().Save(ctx, pipeline); err != nil {
		return "", err
	}
	return pipeline.ID, nil
}

// applyStrategy records where this release's source comes from.
func (s *DeploymentService) applyStrategy(pipeline *domain.Pipeline, strategy DeployStrategy, buildConfig *domain.ApplicationBuildConfig) error {
	if strategy.Type == domain.SourceZip {
		publishConfig, err := domain.ResolveZipPublishConfig(strategy.ObjectKey, strategy.URL, strategy.Repository)
		if err != nil {
			return err
		}
		pipeline.PublishConfig = publishConfig
		return nil
	}
	// The repository is the application's, not the request's: a release chooses a
	// branch, never a different repository.
	var repository *string
	if buildConfig != nil {
		repository = buildConfig.Repository()
	}
	if domain.IsBlank(repository) {
		return domain.Biz("Repository is required for GIT publish")
	}
	branch := domain.NormalizeGitBranch(strategy.Branch)
	pipeline.PublishConfig = &domain.PublishConfig{
		Type: domain.SourceGit, Repository: repository, Branch: domain.StringOrNil(branch),
	}
	return nil
}

// submitBuild creates the build Job and returns the artifact it will push.
func (s *DeploymentService) submitBuild(ctx context.Context, pipeline *domain.Pipeline,
	application *domain.Application, environment *domain.Environment) (string, error) {
	client, err := s.services.Pool.Get(environment.KubernetesApiServer)
	if err != nil {
		return "", err
	}
	input := k8s.PipelineJobInput{
		Pipeline:        pipeline,
		Application:     application,
		BuildConfig:     application.BuildConfig,
		Environment:     environment,
		CloneImage:      s.services.Config.Pipeline.Images.Clone,
		ZipImage:        s.services.Config.Pipeline.Images.Zip,
		PushImage:       s.services.Config.Pipeline.Images.Push,
		RegistryMirrors: s.services.Config.Pipeline.RegistryMirrors,
		UnzipExcludes:   s.services.Config.Pipeline.UnzipExcludes,
	}
	// A ZIP source is presigned here rather than at upload time, so a stored
	// object key can never go stale the way a stored presigned URL would.
	if pipeline.PublishType == domain.SourceZip && pipeline.PublishConfig != nil {
		objectKey := domain.Deref(pipeline.PublishConfig.ObjectKey)
		if strings.TrimSpace(objectKey) != "" {
			url, err := s.services.Storage.CreateDownloadURL(ctx, objectKey)
			if err != nil {
				return "", err
			}
			input.SourceDownloadURL = url
		} else {
			input.SourceDownloadURL = domain.Deref(pipeline.PublishConfig.URL)
		}
	}
	return k8s.StartPipelineJob(ctx, client, input)
}

// SourceUploadCommand is the request for a ZIP upload URL.
type SourceUploadCommand struct {
	FileName    string `json:"fileName"`
	FileSize    *int64 `json:"fileSize"`
	ContentType string `json:"contentType"`
}

// CreateSourceUpload mints a presigned PUT URL so the archive goes straight to
// object storage rather than through this server.
func (s *DeploymentService) CreateSourceUpload(ctx context.Context, namespace, applicationName string, request SourceUploadCommand) (*objectstorage.UploadResult, error) {
	return s.services.Storage.CreateUpload(ctx, namespace, applicationName, request.FileName, request.FileSize, request.ContentType)
}
