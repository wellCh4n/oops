// Package scheduler holds the background jobs: they are the half of the product
// that runs without anyone asking. Each one is a ticker on its own goroutine,
// and each skips a tick it is still busy with rather than piling up.
package scheduler

import (
	"context"
	"log/slog"
	"strings"
	"sync/atomic"
	"time"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/k8s"
	"github.com/wellch4n/oops/server/internal/service"
)

// rolloutTimeout is how long a new version may stay not-ready before the
// pipeline is failed. Long enough for a slow image pull, short enough that a
// wedged release does not sit at ROLLING_OUT forever.
const rolloutTimeout = 5 * time.Minute

// PipelineScan advances pipelines that are waiting on Kubernetes: RUNNING ones
// waiting for their build Job, and ROLLING_OUT ones waiting for the StatefulSet
// to converge. Both halves run on the same 5s tick.
type PipelineScan struct {
	services *service.Services

	// Each half guards itself: a scan that outlives its interval must skip the
	// next tick, not run twice over the same pipelines.
	buildScanning   atomic.Bool
	rolloutScanning atomic.Bool
}

func NewPipelineScan(services *service.Services) *PipelineScan {
	return &PipelineScan{services: services}
}

func (j *PipelineScan) Name() string            { return "pipeline-scan" }
func (j *PipelineScan) Interval() time.Duration { return 5 * time.Second }

func (j *PipelineScan) Run(ctx context.Context) {
	j.scanBuilds(ctx)
	j.scanRollouts(ctx)
}

// scanBuilds moves RUNNING pipelines on once their build Job finishes.
func (j *PipelineScan) scanBuilds(ctx context.Context) {
	if !j.buildScanning.CompareAndSwap(false, true) {
		return
	}
	defer j.buildScanning.Store(false)

	pipelines, err := j.services.Store.Pipelines().FindAllByStatus(ctx, domain.PipelineRunning)
	if err != nil {
		slog.Error("could not list running pipelines", "error", err)
		return
	}
	for index := range pipelines {
		pipeline := &pipelines[index]
		if err := j.advanceBuild(ctx, pipeline); err != nil {
			slog.Error("error scanning a pipeline", "pipeline", pipeline.ID, "error", err)
			j.failFromEitherState(ctx, pipeline, err)
		}
	}
}

func (j *PipelineScan) advanceBuild(ctx context.Context, pipeline *domain.Pipeline) error {
	if domain.IsTerminalStatus(pipeline.Status) {
		return nil
	}
	environment, err := j.services.Environments.FindByName(ctx, pipeline.Environment)
	if err != nil {
		return err
	}
	if environment == nil {
		return domain.Bizf("Environment not found: %s", pipeline.Environment)
	}
	client, err := j.services.Pool.Get(environment.KubernetesApiServer)
	if err != nil {
		return err
	}
	status, err := k8s.PipelineJobStatus(ctx, client, domain.Deref(environment.WorkNamespace), pipeline.Name())
	if err != nil {
		return err
	}
	switch status {
	case k8s.JobSucceeded:
		return j.onBuildSucceeded(ctx, pipeline, environment)
	case k8s.JobFailed:
		slog.Warn("pipeline build failed", "pipeline", pipeline.ID)
		j.transitionWithMessage(ctx, pipeline, domain.PipelineRunning, domain.PipelineError, "镜像构建失败，请查看流水线日志。")
		return nil
	default:
		// Still building; leave it for the next tick.
		return nil
	}
}

// onBuildSucceeded either parks the pipeline for a manual deploy or rolls it out.
func (j *PipelineScan) onBuildSucceeded(ctx context.Context, pipeline *domain.Pipeline, environment *domain.Environment) error {
	if pipeline.DeployMode == domain.DeployManual {
		if err := domain.EnsureCanTransition(domain.PipelineRunning, domain.PipelineBuildSucceeded); err != nil {
			return err
		}
		j.transition(ctx, pipeline, domain.PipelineRunning, domain.PipelineBuildSucceeded, "镜像构建完成，等待手动发布。")
		return nil
	}
	if err := domain.EnsureCanTransition(domain.PipelineRunning, domain.PipelineDeploying); err != nil {
		return err
	}
	claimed, err := j.services.Store.Pipelines().UpdateStatusIfMatch(ctx, pipeline.ID, domain.PipelineRunning, domain.PipelineDeploying)
	if err != nil {
		return err
	}
	if claimed == 0 {
		// Someone else advanced it; theirs is the deploy that runs.
		return nil
	}
	pipeline.Status = domain.PipelineDeploying
	j.services.Pipelines.Notify(ctx, pipeline, "发布任务已进入部署阶段。")

	application, err := j.services.Store.Applications().FindAggregate(ctx, pipeline.Namespace, pipeline.ApplicationName)
	if err != nil {
		return err
	}
	if application == nil {
		return domain.Bizf("Application not found: %s/%s", pipeline.Namespace, pipeline.ApplicationName)
	}
	domains, err := j.services.Store.Domains().FindAll(ctx)
	if err != nil {
		return err
	}
	if err := k8s.Deploy(ctx, k8s.DeployInput{
		Pipeline:      pipeline,
		Application:   application,
		Environment:   environment,
		RuntimeSpec:   application.RuntimeEnvironmentConfigOrDefault(pipeline.Environment),
		HealthCheck:   application.HealthCheckOrDefault(),
		ServiceConfig: application.ServiceConfigOrDefault(),
		ExpertConfig:  application.ExpertEnvironmentConfigOrDefault(pipeline.Environment),
		Domains:       domains,
		CertResolver:  j.services.Config.Ingress.CertResolver,
	}); err != nil {
		return err
	}
	if err := domain.EnsureCanTransition(domain.PipelineDeploying, domain.PipelineRollingOut); err != nil {
		return err
	}
	j.transition(ctx, pipeline, domain.PipelineDeploying, domain.PipelineRollingOut, "正在等待新版本发布生效…")
	return nil
}

// scanRollouts decides whether a ROLLING_OUT pipeline has converged, failed, or
// run out of time.
func (j *PipelineScan) scanRollouts(ctx context.Context) {
	if !j.rolloutScanning.CompareAndSwap(false, true) {
		return
	}
	defer j.rolloutScanning.Store(false)

	pipelines, err := j.services.Store.Pipelines().FindAllByStatus(ctx, domain.PipelineRollingOut)
	if err != nil {
		slog.Error("could not list rolling-out pipelines", "error", err)
		return
	}
	for index := range pipelines {
		pipeline := &pipelines[index]
		if err := j.advanceRollout(ctx, pipeline); err != nil {
			// Unlike the build scan, a failure here is not turned into a failed
			// pipeline: an unreachable cluster for one tick must not fail a
			// release that is rolling out perfectly well.
			slog.Error("error rolling out a pipeline", "pipeline", pipeline.ID, "error", err)
		}
	}
}

func (j *PipelineScan) advanceRollout(ctx context.Context, pipeline *domain.Pipeline) error {
	environment, err := j.services.Environments.FindByName(ctx, pipeline.Environment)
	if err != nil {
		return err
	}
	if environment == nil {
		return domain.Bizf("Environment not found: %s", pipeline.Environment)
	}
	health, err := j.services.Runtime.GetDeploymentHealth(ctx, environment, pipeline.Namespace, pipeline.ApplicationName)
	if err != nil {
		return err
	}
	switch {
	case health.WorkloadMissing:
		j.failRollout(ctx, pipeline, "新版本部署失败：StatefulSet 不存在。")
	case health.HasFailure():
		j.failRollout(ctx, pipeline, "新版本部署失败："+health.FailureReason)
	case health.RolloutComplete:
		if err := domain.EnsureCanTransition(domain.PipelineRollingOut, domain.PipelineSucceeded); err != nil {
			return err
		}
		j.transition(ctx, pipeline, domain.PipelineRollingOut, domain.PipelineSucceeded, "应用已经成功发布。")
	case health.NotReadyLongerThan(time.Now(), rolloutTimeout):
		j.failRollout(ctx, pipeline, "发布生效超时，新版本未在规定时间内就绪。")
	}
	// Otherwise it is still rolling out; leave it for the next tick.
	return nil
}

func (j *PipelineScan) failRollout(ctx context.Context, pipeline *domain.Pipeline, message string) {
	if err := domain.EnsureCanTransition(domain.PipelineRollingOut, domain.PipelineError); err != nil {
		slog.Error("refusing an invalid rollout transition", "pipeline", pipeline.ID, "error", err)
		return
	}
	j.transitionWithMessage(ctx, pipeline, domain.PipelineRollingOut, domain.PipelineError, message)
}

// transition claims a status change and notifies only if it won the race.
func (j *PipelineScan) transition(ctx context.Context, pipeline *domain.Pipeline, from, to domain.PipelineStatus, detail string) {
	updated, err := j.services.Store.Pipelines().UpdateStatusIfMatch(ctx, pipeline.ID, from, to)
	if err != nil {
		slog.Error("could not record a pipeline transition", "pipeline", pipeline.ID, "error", err)
		return
	}
	if updated == 0 {
		return
	}
	pipeline.Status = to
	j.services.Pipelines.Notify(ctx, pipeline, detail)
}

func (j *PipelineScan) transitionWithMessage(ctx context.Context, pipeline *domain.Pipeline, from, to domain.PipelineStatus, message string) {
	updated, err := j.services.Store.Pipelines().UpdateStatusAndMessageIfMatch(ctx, pipeline.ID, from, to, &message)
	if err != nil {
		slog.Error("could not record a pipeline transition", "pipeline", pipeline.ID, "error", err)
		return
	}
	if updated == 0 {
		return
	}
	pipeline.Status = to
	pipeline.Message = &message
	j.services.Pipelines.Notify(ctx, pipeline, message)
}

// failFromEitherState records a scan failure. The pipeline may be in RUNNING or
// in DEPLOYING depending on how far the tick got before it failed, so both are
// attempted and only the one that matches takes effect.
func (j *PipelineScan) failFromEitherState(ctx context.Context, pipeline *domain.Pipeline, cause error) {
	message := strings.TrimSpace(cause.Error())
	if message == "" {
		message = "发布任务执行失败，请查看日志。"
	}
	pipelines := j.services.Store.Pipelines()
	deploying, _ := pipelines.UpdateStatusAndMessageIfMatch(ctx, pipeline.ID, domain.PipelineDeploying, domain.PipelineError, &message)
	running, _ := pipelines.UpdateStatusAndMessageIfMatch(ctx, pipeline.ID, domain.PipelineRunning, domain.PipelineError, &message)
	if deploying > 0 || running > 0 {
		pipeline.Status = domain.PipelineError
		pipeline.Message = &message
		j.services.Pipelines.Notify(ctx, pipeline, message)
	}
}
