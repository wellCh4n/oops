package service

import (
	"context"
	"log/slog"
	"strings"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/k8s"
	"github.com/wellch4n/oops/server/internal/store"
)

// PipelineService drives the pipeline state machine: listing, manual deploy,
// rollback and stop. Every transition goes through a conditional UPDATE, so two
// workers racing on the same pipeline cannot both win.
type PipelineService struct {
	services *Services
}

// activeStatuses are the in-flight statuses the duplicate-deploy guard checks
// for. A pipeline in any of them owns the application until it finishes.
func activeStatuses() []domain.PipelineStatus {
	return []domain.PipelineStatus{domain.PipelineRunning, domain.PipelineDeploying, domain.PipelineRollingOut}
}

func (s *PipelineService) repo() *store.PipelineRepository { return s.services.Store.Pipelines() }

// ensureNoActivePipeline is the pre-check that stops a second deploy being
// started at all. It is separate from the optimistic locking, which only stops
// two workers from advancing the same pipeline.
func (s *PipelineService) ensureNoActivePipeline(ctx context.Context, namespace, applicationName string) error {
	active, err := s.repo().ExistsByStatusIn(ctx, namespace, applicationName, activeStatuses())
	if err != nil {
		return err
	}
	if active {
		return domain.ErrApplicationBeingDeployed()
	}
	return nil
}

// requireOperableApplication loads the application and checks the caller may
// operate it (owner, collaborator or admin).
func (s *PipelineService) requireOperableApplication(ctx context.Context, namespace, applicationName, operatorUserID string) (*domain.Application, error) {
	application, err := s.services.Store.Applications().FindAggregate(ctx, namespace, applicationName)
	if err != nil {
		return nil, err
	}
	operator, err := s.services.operator(ctx, operatorUserID)
	if err != nil {
		return nil, err
	}
	if err := domain.EnsureCanOperate(application, operator); err != nil {
		return nil, err
	}
	return application, nil
}

func (s *PipelineService) requirePipeline(ctx context.Context, namespace, applicationName, id string) (*domain.Pipeline, error) {
	pipeline, err := s.repo().Find(ctx, namespace, applicationName, id)
	if err != nil {
		return nil, err
	}
	if pipeline == nil {
		return nil, domain.Biz("Pipeline not found")
	}
	return pipeline, nil
}

// ---------------------------------------------------------------------------
// reads

func (s *PipelineService) List(ctx context.Context, namespace, applicationName, environment string, page, size int) (store.Page[PipelineView], error) {
	if page <= 0 {
		page = 1
	}
	if size <= 0 {
		size = 20
	}
	result, err := s.repo().FindPage(ctx, namespace, applicationName, environment, page, size)
	if err != nil {
		return store.Page[PipelineView]{}, err
	}
	views, err := s.toViews(ctx, result.Data)
	if err != nil {
		return store.Page[PipelineView]{}, err
	}
	return store.Page[PipelineView]{Total: result.Total, Data: views, Size: result.Size, TotalPages: result.TotalPages}, nil
}

func (s *PipelineService) Get(ctx context.Context, namespace, applicationName, id string) (*PipelineView, error) {
	pipeline, err := s.repo().Find(ctx, namespace, applicationName, id)
	if err != nil || pipeline == nil {
		return nil, err
	}
	views, err := s.toViews(ctx, []domain.Pipeline{*pipeline})
	if err != nil || len(views) == 0 {
		return nil, err
	}
	return &views[0], nil
}

// toViews resolves the operator names for a batch in one query.
func (s *PipelineService) toViews(ctx context.Context, pipelines []domain.Pipeline) ([]PipelineView, error) {
	operatorIDs := map[string]bool{}
	for _, pipeline := range pipelines {
		if id := domain.Deref(pipeline.OperatorID); id != "" {
			operatorIDs[id] = true
		}
	}
	names, err := s.services.Users.UsernamesByID(ctx, keysOf(operatorIDs))
	if err != nil {
		return nil, err
	}
	views := make([]PipelineView, 0, len(pipelines))
	for index := range pipelines {
		pipeline := pipelines[index]
		var operatorName *string
		if name, ok := names[domain.Deref(pipeline.OperatorID)]; ok {
			operatorName = &name
		}
		views = append(views, pipelineView(&pipeline, operatorName))
	}
	return views, nil
}

// ActiveDeployments lists the in-flight deployments, newest first. A namespace
// of "all" spans every namespace.
func (s *PipelineService) ActiveDeployments(ctx context.Context, namespace string) ([]ActiveDeploymentView, error) {
	var pipelines []domain.Pipeline
	var err error
	if strings.EqualFold(namespace, "all") {
		pipelines, err = s.repo().FindByStatusIn(ctx, activeStatuses())
	} else {
		pipelines, err = s.repo().FindByNamespaceAndStatusIn(ctx, namespace, activeStatuses())
	}
	if err != nil {
		return nil, err
	}
	sortByCreatedTimeDesc(pipelines)
	views := make([]ActiveDeploymentView, 0, len(pipelines))
	for _, pipeline := range pipelines {
		views = append(views, ActiveDeploymentView{
			Namespace: pipeline.Namespace, ApplicationName: pipeline.ApplicationName,
			PipelineID: pipeline.ID, Environment: pipeline.Environment,
			Status: pipeline.Status, CreatedTime: pipeline.CreatedTime,
		})
	}
	return views, nil
}

// LastSuccessful pre-fills the deploy dialog from the last release.
func (s *PipelineService) LastSuccessful(ctx context.Context, namespace, applicationName string) (*LastSuccessfulPipelineView, error) {
	pipeline, err := s.repo().FindLatestByStatus(ctx, namespace, applicationName, domain.PipelineSucceeded)
	if err != nil || pipeline == nil {
		return nil, err
	}
	return &LastSuccessfulPipelineView{
		DeployMode: pipeline.DeployMode, PublishType: pipeline.PublishType, PublishConfig: pipeline.PublishConfig,
	}, nil
}

// ---------------------------------------------------------------------------
// transitions

// Deploy is the manual half of a MANUAL-mode pipeline: the image is already
// built, this rolls it out.
func (s *PipelineService) Deploy(ctx context.Context, namespace, applicationName, id, operatorUserID string) error {
	pipeline, err := s.requirePipeline(ctx, namespace, applicationName, id)
	if err != nil {
		return err
	}
	application, err := s.requireOperableApplication(ctx, namespace, applicationName, operatorUserID)
	if err != nil {
		return err
	}
	if err := domain.EnsureManualDeployable(pipeline.Status); err != nil {
		return err
	}
	if err := s.ensureNoActivePipeline(ctx, namespace, applicationName); err != nil {
		return err
	}
	if err := domain.EnsureCanTransition(domain.PipelineBuildSucceeded, domain.PipelineDeploying); err != nil {
		return err
	}
	claimed, err := s.repo().UpdateStatusIfMatch(ctx, pipeline.ID, domain.PipelineBuildSucceeded, domain.PipelineDeploying)
	if err != nil {
		return err
	}
	if claimed == 0 {
		return domain.Biz("Pipeline state changed concurrently, please retry")
	}
	pipeline.Status = domain.PipelineDeploying
	s.Notify(ctx, pipeline, "发布任务已进入部署阶段。")
	return s.runDeploy(ctx, pipeline, application, "正在等待新版本发布生效…", "Deploy failed", "发布任务执行失败，请查看日志。")
}

// Rollback re-deploys a previous release's artifact as a new pipeline, so the
// history keeps a record of the rollback rather than the old pipeline appearing
// to run twice.
func (s *PipelineService) Rollback(ctx context.Context, namespace, applicationName, targetPipelineID, operatorUserID string) (string, error) {
	source, err := s.repo().Find(ctx, namespace, applicationName, targetPipelineID)
	if err != nil {
		return "", err
	}
	if source == nil {
		return "", domain.Biz("Target pipeline not found")
	}
	if source.Status != domain.PipelineSucceeded {
		return "", domain.Biz("Only succeeded pipelines can be rolled back to")
	}
	if domain.IsBlank(source.Artifact) {
		return "", domain.Biz("Target pipeline has no artifact to deploy")
	}
	application, err := s.requireOperableApplication(ctx, namespace, applicationName, operatorUserID)
	if err != nil {
		return "", err
	}
	if err := s.ensureNoActivePipeline(ctx, namespace, applicationName); err != nil {
		return "", err
	}
	pipeline, err := s.repo().Save(ctx, domain.RollbackPipeline(source, operatorUserID))
	if err != nil {
		return "", err
	}
	s.Notify(ctx, pipeline, "回滚任务已创建。")

	if err := domain.EnsureCanTransition(domain.PipelineInitialized, domain.PipelineDeploying); err != nil {
		return "", err
	}
	claimed, err := s.repo().UpdateStatusIfMatch(ctx, pipeline.ID, domain.PipelineInitialized, domain.PipelineDeploying)
	if err != nil {
		return "", err
	}
	if claimed == 0 {
		return "", domain.Biz("Pipeline state changed concurrently, please retry")
	}
	pipeline.Status = domain.PipelineDeploying
	s.Notify(ctx, pipeline, "回滚任务已进入部署阶段。")
	if err := s.runDeploy(ctx, pipeline, application, "正在等待回滚版本发布生效…", "Rollback failed", "回滚任务执行失败，请查看日志。"); err != nil {
		return "", err
	}
	return pipeline.ID, nil
}

// runDeploy applies the artifact and advances DEPLOYING -> ROLLING_OUT, or
// records the failure. From ROLLING_OUT the scan job takes over.
func (s *PipelineService) runDeploy(ctx context.Context, pipeline *domain.Pipeline, application *domain.Application,
	rollingOutDetail, failurePrefix, defaultFailure string) error {
	environment, err := s.services.environmentByName(ctx, pipeline.Environment)
	if err == nil {
		var domains []domain.Domain
		domains, err = s.services.Store.Domains().FindAll(ctx)
		if err == nil {
			err = k8s.Deploy(ctx, k8s.DeployInput{
				Pipeline:      pipeline,
				Application:   application,
				Environment:   environment,
				RuntimeSpec:   application.RuntimeEnvironmentConfigOrDefault(pipeline.Environment),
				HealthCheck:   application.HealthCheckOrDefault(),
				ServiceConfig: application.ServiceConfigOrDefault(),
				ExpertConfig:  application.ExpertEnvironmentConfigOrDefault(pipeline.Environment),
				Domains:       domains,
				CertResolver:  s.services.Config.Ingress.CertResolver,
			})
		}
	}
	if err != nil {
		message := strings.TrimSpace(err.Error())
		if message == "" {
			message = defaultFailure
		}
		if _, updateErr := s.repo().UpdateStatusAndMessageIfMatch(ctx, pipeline.ID,
			domain.PipelineDeploying, domain.PipelineError, &message); updateErr != nil {
			slog.Error("could not record the deploy failure", "pipeline", pipeline.ID, "error", updateErr)
		}
		pipeline.Status = domain.PipelineError
		pipeline.Message = &message
		s.Notify(ctx, pipeline, message)
		return domain.BizWrap(failurePrefix+": "+err.Error(), err)
	}

	if err := domain.EnsureCanTransition(domain.PipelineDeploying, domain.PipelineRollingOut); err != nil {
		return err
	}
	if _, err := s.repo().UpdateStatusIfMatch(ctx, pipeline.ID, domain.PipelineDeploying, domain.PipelineRollingOut); err != nil {
		return err
	}
	pipeline.Status = domain.PipelineRollingOut
	s.Notify(ctx, pipeline, rollingOutDetail)
	return nil
}

// Stop cancels a pipeline. One that has already built has no Job left to kill,
// so it only changes state.
func (s *PipelineService) Stop(ctx context.Context, namespace, applicationName, id, operatorUserID string) error {
	pipeline, err := s.requirePipeline(ctx, namespace, applicationName, id)
	if err != nil {
		return err
	}
	if _, err := s.requireOperableApplication(ctx, namespace, applicationName, operatorUserID); err != nil {
		return err
	}
	if err := domain.EnsureCanTransition(pipeline.Status, domain.PipelineStopped); err != nil {
		return err
	}
	if pipeline.Status != domain.PipelineBuildSucceeded {
		environment, err := s.services.environmentByName(ctx, pipeline.Environment)
		if err != nil {
			return err
		}
		client, err := s.services.Pool.Get(environment.KubernetesApiServer)
		if err != nil {
			return err
		}
		if err := k8s.DeletePipelineJob(ctx, client, domain.Deref(environment.WorkNamespace), pipeline.Name()); err != nil {
			return err
		}
	}
	pipeline.Status = domain.PipelineStopped
	if _, err := s.repo().Save(ctx, pipeline); err != nil {
		return err
	}
	s.Notify(ctx, pipeline, "发布任务已被手动停止。")
	return nil
}

// Notify tells the pipeline's operator where it has got to. It never fails the
// transition: a message that could not be delivered must not undo a deploy.
func (s *PipelineService) Notify(ctx context.Context, pipeline *domain.Pipeline, detail string) {
	operatorID := domain.Deref(pipeline.OperatorID)
	if operatorID == "" {
		return
	}
	title := pipeline.ApplicationName + " · " + string(pipeline.Status)
	s.services.Notifier.Notify(ctx, operatorID, title, detail)
}

// sortByCreatedTimeDesc orders pipelines newest first, with unset times last.
func sortByCreatedTimeDesc(pipelines []domain.Pipeline) {
	for i := 1; i < len(pipelines); i++ {
		for j := i; j > 0 && newerThan(pipelines[j], pipelines[j-1]); j-- {
			pipelines[j], pipelines[j-1] = pipelines[j-1], pipelines[j]
		}
	}
}

func newerThan(left, right domain.Pipeline) bool {
	if !left.CreatedTime.Valid {
		return false
	}
	if !right.CreatedTime.Valid {
		return true
	}
	return left.CreatedTime.Time.After(right.CreatedTime.Time)
}

// FindPipeline loads one pipeline for the log stream, which needs the whole
// record — its environment, its Job name and whether it has finished — rather
// than the view the REST endpoints answer with.
func (s *PipelineService) FindPipeline(ctx context.Context, namespace, applicationName, id string) (*domain.Pipeline, error) {
	return s.repo().Find(ctx, namespace, applicationName, id)
}
