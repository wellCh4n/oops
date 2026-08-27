package engine

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/store"
)

// DeployRequest mirrors DeployCommand: strategy plus deploy mode.
type DeployRequest struct {
	Environment string `json:"environment"`
	DeployMode  string `json:"deployMode"`
	Strategy    *struct {
		Type       string  `json:"type"`
		Branch     *string `json:"branch"`
		ObjectKey  *string `json:"objectKey"`
		URL        *string `json:"url"`
		Repository *string `json:"repository"`
	} `json:"strategy"`
}

// IsBizError reports whether the failure carries a user-facing message.
func IsBizError(err error) bool { return domain.IsBizError(err) }

func bizf(format string, args ...any) error { return domain.Bizf(format, args...) }

// DeployApplication mirrors DeploymentService.deployApplication.
func (engine *Engine) DeployApplication(ctx context.Context, namespace, applicationName string, request *DeployRequest, operatorID string) (string, error) {
	if request == nil {
		return "", bizf("Deploy request is required")
	}
	if request.Strategy == nil {
		return "", bizf("Deploy strategy is required")
	}
	if _, err := engine.Store.FindApplication(ctx, namespace, applicationName); err != nil {
		return "", bizf("Application not found")
	}
	active, err := engine.Store.HasActivePipeline(ctx, namespace, applicationName)
	if err != nil {
		return "", err
	}
	if active {
		return "", bizf("Application is being deployed")
	}
	environment, err := engine.Store.FindEnvironmentFullByName(ctx, request.Environment)
	if err != nil {
		return "", bizf("Environment not found: %s", request.Environment)
	}
	buildConfig, err := engine.Store.FindBuildConfig(ctx, namespace, applicationName)
	if err != nil {
		return "", bizf("Application build config not found.")
	}

	sourceType := "GIT"
	if buildConfig.SourceType != nil && *buildConfig.SourceType != "" {
		sourceType = *buildConfig.SourceType
	}
	publishType := strings.ToUpper(request.Strategy.Type)
	if publishType != sourceType {
		return "", bizf("Deploy strategy %s does not match application source type %s", publishType, sourceType)
	}

	deployMode := request.DeployMode
	if deployMode == "" {
		deployMode = "IMMEDIATE"
	}

	var publishConfig any
	var git *store.GitPublishConfig
	var zip *store.ZipPublishConfig
	if publishType == "ZIP" {
		zip = &store.ZipPublishConfig{
			Type:       "ZIP",
			ObjectKey:  request.Strategy.ObjectKey,
			URL:        request.Strategy.URL,
			Repository: request.Strategy.Repository,
		}
		if (zip.URL == nil || *zip.URL == "") && (zip.ObjectKey == nil || *zip.ObjectKey == "") {
			return "", bizf("ZIP publish requires an object key or download URL")
		}
		// Presign at build time so a stored object key never goes stale.
		if (zip.URL == nil || *zip.URL == "") && engine.ResolveZipURL != nil {
			resolved, err := engine.ResolveZipURL(ctx, *zip.ObjectKey)
			if err != nil {
				return "", err
			}
			zip = &store.ZipPublishConfig{Type: "ZIP", ObjectKey: zip.ObjectKey, URL: &resolved, Repository: zip.Repository}
		}
		publishConfig = zip
	} else {
		branch := "main"
		if request.Strategy.Branch != nil && strings.TrimSpace(*request.Strategy.Branch) != "" {
			branch = strings.TrimSpace(*request.Strategy.Branch)
		}
		if buildConfig.Repository == nil || *buildConfig.Repository == "" {
			return "", bizf("Repository is required for GIT publish")
		}
		git = &store.GitPublishConfig{Type: "GIT", Repository: *buildConfig.Repository, Branch: branch}
		publishConfig = git
	}

	pipelineID, err := engine.Store.CreatePipeline(ctx, namespace, applicationName, environment.Name,
		publishType, publishConfig, deployMode, operatorID, "RELEASE", "")
	if err != nil {
		return "", err
	}
	pipelineName := fmt.Sprintf("%s-pipeline-%s", applicationName, pipelineID)
	if created, err := engine.Store.FindPipeline(ctx, namespace, applicationName, pipelineID); err == nil {
		engine.notifyPipeline(created, "CREATED", "发布流程已经启动，正在构建镜像。")
	}

	cluster, _, err := engine.cluster(ctx, environment.Name)
	if err != nil {
		return "", err
	}
	repositoryHost := ""
	if environment.ImageRepository != nil && environment.ImageRepository.URL != nil {
		repositoryHost = strings.NewReplacer("http://", "", "https://", "").Replace(*environment.ImageRepository.URL)
	}
	workNamespace := ""
	if environment.WorkNamespace != nil {
		workNamespace = *environment.WorkNamespace
	}

	buildCommand := ""
	for _, config := range buildConfig.EnvironmentConfigs {
		if config.EnvironmentName != nil && *config.EnvironmentName == environment.Name && config.BuildCommand != nil {
			buildCommand = *config.BuildCommand
		}
	}
	buildImage := ""
	if buildConfig.BuildImage != nil {
		buildImage = *buildConfig.BuildImage
	}

	artifact, err := SubmitBuild(ctx, cluster, &buildJobInput{
		PipelineID:      pipelineID,
		PipelineName:    pipelineName,
		Namespace:       namespace,
		ApplicationName: applicationName,
		WorkNamespace:   workNamespace,
		RepositoryHost:  repositoryHost,
		BuildImage:      buildImage,
		BuildCommand:    buildCommand,
		DockerFile:      buildConfig.DockerFileConfig,
		Git:             git,
		Zip:             zip,
		Images:          engine.Images,
	})
	if err != nil {
		message := err.Error()
		_, _ = engine.Store.UpdatePipelineStatusAndMessageIfMatch(ctx, pipelineID, store.StatusInitialized, store.StatusError, message)
		return "", err
	}
	if err := engine.Store.UpdatePipelineArtifact(ctx, pipelineID, artifact); err != nil {
		return "", err
	}
	if _, err := engine.Store.UpdatePipelineStatusIfMatch(ctx, pipelineID, store.StatusInitialized, store.StatusRunning); err != nil {
		return "", err
	}
	return pipelineID, nil
}

// ManualDeploy mirrors PipelineService.deployPipeline.
func (engine *Engine) ManualDeploy(ctx context.Context, namespace, applicationName, id string) error {
	pipeline, err := engine.Store.FindPipeline(ctx, namespace, applicationName, id)
	if err != nil {
		return bizf("Pipeline not found")
	}
	if pipeline.Status == nil || *pipeline.Status != store.StatusBuildSucceeded {
		return bizf("Pipeline is not in BUILD_SUCCEEDED state")
	}
	active, err := engine.Store.HasActivePipeline(ctx, namespace, applicationName)
	if err != nil {
		return err
	}
	if active {
		return bizf("Application is being deployed")
	}
	claimed, err := engine.Store.UpdatePipelineStatusIfMatch(ctx, id, store.StatusBuildSucceeded, store.StatusDeploying)
	if err != nil {
		return err
	}
	if claimed == 0 {
		return bizf("Pipeline state changed concurrently, please retry")
	}
	return engine.finishDeployPhase(ctx, pipeline)
}

// Rollback mirrors PipelineService.rollback: a new ROLLBACK pipeline reusing
// the historic artifact, skipping the build entirely.
func (engine *Engine) Rollback(ctx context.Context, namespace, applicationName, targetPipelineID, operatorID string) (string, error) {
	source, err := engine.Store.FindPipeline(ctx, namespace, applicationName, targetPipelineID)
	if err != nil {
		return "", bizf("Target pipeline not found")
	}
	if source.Status == nil || *source.Status != store.StatusSucceeded {
		return "", bizf("Only succeeded pipelines can be rolled back to")
	}
	if source.Artifact == nil || *source.Artifact == "" {
		return "", bizf("Target pipeline has no artifact to deploy")
	}
	active, err := engine.Store.HasActivePipeline(ctx, namespace, applicationName)
	if err != nil {
		return "", err
	}
	if active {
		return "", bizf("Application is being deployed")
	}
	environmentName := ""
	if source.Environment != nil {
		environmentName = *source.Environment
	}
	publishType := "GIT"
	if source.PublishType != nil {
		publishType = *source.PublishType
	}
	var publishConfig any
	if len(source.PublishConfig) > 0 {
		var decoded map[string]any
		if json.Unmarshal(source.PublishConfig, &decoded) == nil {
			publishConfig = decoded
		}
	}
	rollbackID, err := engine.Store.CreatePipeline(ctx, namespace, applicationName, environmentName,
		publishType, publishConfig, "IMMEDIATE", operatorID, "ROLLBACK", source.ID)
	if err != nil {
		return "", err
	}
	if err := engine.Store.UpdatePipelineArtifact(ctx, rollbackID, *source.Artifact); err != nil {
		return "", err
	}
	claimed, err := engine.Store.UpdatePipelineStatusIfMatch(ctx, rollbackID, store.StatusInitialized, store.StatusDeploying)
	if err != nil {
		return "", err
	}
	if claimed == 0 {
		return "", bizf("Pipeline state changed concurrently, please retry")
	}
	rollbackPipeline, err := engine.Store.FindPipeline(ctx, namespace, applicationName, rollbackID)
	if err != nil {
		return "", err
	}
	if err := engine.finishDeployPhase(ctx, rollbackPipeline); err != nil {
		return "", bizf("Rollback failed: %s", err.Error())
	}
	return rollbackID, nil
}

// finishDeployPhase applies the artifact and moves DEPLOYING → ROLLING_OUT,
// marking ERROR on failure like the Java catch blocks.
func (engine *Engine) finishDeployPhase(ctx context.Context, pipeline *store.PipelineView) error {
	environmentName := ""
	if pipeline.Environment != nil {
		environmentName = *pipeline.Environment
	}
	cluster, environment, err := engine.cluster(ctx, environmentName)
	if err == nil {
		err = engine.deployArtifact(ctx, cluster, environment, pipeline)
	}
	if err != nil {
		message := err.Error()
		_, _ = engine.Store.UpdatePipelineStatusAndMessageIfMatch(ctx, pipeline.ID, store.StatusDeploying, store.StatusError, message)
		return err
	}
	_, err = engine.Store.UpdatePipelineStatusIfMatch(ctx, pipeline.ID, store.StatusDeploying, store.StatusRollingOut)
	return err
}

// DeployImageTo applies the deploy chain for an arbitrary image in a target
// namespace (namespace migration's redeploy step).
func (engine *Engine) DeployImageTo(ctx context.Context, targetNamespace, applicationName, environmentName, image string) error {
	pipeline := &store.PipelineView{
		Namespace:       targetNamespace,
		ApplicationName: applicationName,
		Environment:     &environmentName,
		Artifact:        &image,
	}
	cluster, environment, err := engine.cluster(ctx, environmentName)
	if err != nil {
		return err
	}
	return engine.deployArtifact(ctx, cluster, environment, pipeline)
}

// Stop mirrors PipelineService.stopPipeline: suspend the running build Job
// (BUILD_SUCCEEDED pipelines have no Job to touch) and mark STOPPED.
func (engine *Engine) Stop(ctx context.Context, namespace, applicationName, id string) error {
	pipeline, err := engine.Store.FindPipeline(ctx, namespace, applicationName, id)
	if err != nil {
		return bizf("Pipeline not found")
	}
	current := ""
	if pipeline.Status != nil {
		current = *pipeline.Status
	}
	if err := domain.EnsurePipelineTransition(current, store.StatusStopped); err != nil {
		return bizf("%s", err.Error())
	}
	if current != store.StatusBuildSucceeded {
		environmentName := ""
		if pipeline.Environment != nil {
			environmentName = *pipeline.Environment
		}
		cluster, environment, err := engine.cluster(ctx, environmentName)
		if err != nil {
			return err
		}
		workNamespace := ""
		if environment.WorkNamespace != nil {
			workNamespace = *environment.WorkNamespace
		}
		suspendPatch := []byte(`{"spec":{"suspend":true}}`)
		_, _ = cluster.Clientset.BatchV1().Jobs(workNamespace).
			Patch(ctx, pipeline.Name, types.MergePatchType, suspendPatch, metav1.PatchOptions{})
	}
	_, err = engine.Store.UpdatePipelineStatusIfMatch(ctx, id, current, store.StatusStopped)
	if err == nil {
		engine.notifyPipeline(pipeline, "STOPPED", "发布任务已被手动停止。")
	}
	return err
}
