// Package engine runs the pipeline lifecycle: build jobs, the deploy
// processor chain, scan loops that converge pipeline status, scheduled
// restarts, resource alert scans, and namespace migration.
package engine

import (
	"context"
	"log"
	"strings"
	"time"

	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/k8s"
	"github.com/wellch4n/oops/server/internal/store"
)

const rolloutTimeout = 5 * time.Minute

// Engine owns the pipeline lifecycle: triggering deployments and running the
// scan loops that advance pipeline state, mirroring DeploymentService +
// PipelineInstanceScanJob.
type Engine struct {
	Store        *store.Store
	Images       ImageConfig
	CertResolver string
	Notifier     Notifier
	// ResolveZipURL presigns a ZIP object key at build time (nil when object
	// storage is disabled).
	ResolveZipURL func(ctx context.Context, repositoryOrKey string) (string, error)
}

func (engine *Engine) cluster(ctx context.Context, environmentName string) (*k8s.Cluster, *store.EnvironmentFull, error) {
	environment, err := engine.Store.FindEnvironmentFullByName(ctx, environmentName)
	if err != nil {
		return nil, nil, err
	}
	url, token := "", ""
	if environment.KubernetesAPIServer != nil {
		if environment.KubernetesAPIServer.URL != nil {
			url = *environment.KubernetesAPIServer.URL
		}
		if environment.KubernetesAPIServer.Token != nil {
			token = *environment.KubernetesAPIServer.Token
		}
	}
	cluster, err := k8s.NewCluster(url, token)
	return cluster, environment, err
}

// Run starts the scan loops (5s cadence) and the minute-aligned schedulers.
func (engine *Engine) Run(ctx context.Context) {
	go engine.loop(ctx, engine.scanPipelineJobs)
	go engine.loop(ctx, engine.scanRollingOutPipelines)
	engine.RunScheduledRestarts(ctx)
}

func (engine *Engine) loop(ctx context.Context, scan func(context.Context)) {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			scan(ctx)
		}
	}
}

func (engine *Engine) scanPipelineJobs(ctx context.Context) {
	pipelines, err := engine.Store.FindPipelinesByStatus(ctx, domain.PipelineRunning)
	if err != nil {
		log.Printf("pipeline scan: %v", err)
		return
	}
	for i := range pipelines {
		pipeline := &pipelines[i]
		if err := engine.scanOneRunning(ctx, pipeline); err != nil {
			log.Printf("pipeline %s scan error: %v", pipeline.ID, err)
			message := err.Error()
			if message == "" {
				message = "发布任务执行失败，请查看日志。"
			}
			deployingUpdated, _ := engine.Store.UpdatePipelineStatusAndMessageIfMatch(ctx, pipeline.ID, domain.PipelineDeploying, domain.PipelineError, message)
			runningUpdated, _ := engine.Store.UpdatePipelineStatusAndMessageIfMatch(ctx, pipeline.ID, domain.PipelineRunning, domain.PipelineError, message)
			if deployingUpdated > 0 || runningUpdated > 0 {
				log.Printf("pipeline %s marked ERROR: %s", pipeline.ID, message)
			}
		}
	}
}

func (engine *Engine) scanOneRunning(ctx context.Context, pipeline *store.PipelineView) error {
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
	job, err := cluster.Clientset.BatchV1().Jobs(workNamespace).Get(ctx, pipeline.Name, metav1.GetOptions{})
	if apierrors.IsNotFound(err) {
		return nil // UNKNOWN — leave for the next tick
	}
	if err != nil {
		return err
	}
	switch {
	case job.Status.Succeeded == 1:
		if pipeline.DeployMode != nil && *pipeline.DeployMode == "MANUAL" {
			updated, err := engine.Store.UpdatePipelineStatusIfMatch(ctx, pipeline.ID, domain.PipelineRunning, domain.PipelineBuildSucceeded)
			if err == nil && updated > 0 {
				log.Printf("pipeline %s build succeeded, awaiting manual deploy", pipeline.ID)
				engine.notifyPipeline(pipeline, "BUILD_SUCCEEDED", "镜像构建完成，等待手动发布。")
			}
			return err
		}
		claimed, err := engine.Store.UpdatePipelineStatusIfMatch(ctx, pipeline.ID, domain.PipelineRunning, domain.PipelineDeploying)
		if err != nil || claimed == 0 {
			return err
		}
		log.Printf("pipeline %s deploying", pipeline.ID)
		engine.notifyPipeline(pipeline, "DEPLOYING", "发布任务已进入部署阶段。")
		if err := engine.deployArtifact(ctx, cluster, environment, pipeline); err != nil {
			return err
		}
		if _, err := engine.Store.UpdatePipelineStatusIfMatch(ctx, pipeline.ID, domain.PipelineDeploying, domain.PipelineRollingOut); err != nil {
			return err
		}
		log.Printf("pipeline %s rolling out", pipeline.ID)
		engine.notifyPipeline(pipeline, "ROLLING_OUT", "正在等待新版本发布生效…")
	case job.Status.Failed > 0:
		message := "镜像构建失败，请查看流水线日志。"
		updated, err := engine.Store.UpdatePipelineStatusAndMessageIfMatch(ctx, pipeline.ID, domain.PipelineRunning, domain.PipelineError, message)
		if err == nil && updated > 0 {
			log.Printf("pipeline %s build failed", pipeline.ID)
			engine.notifyPipeline(pipeline, "FAILED", message)
		}
		return err
	}
	return nil
}

// DeployArtifact runs the deploy chain for a pipeline whose artifact is ready
// (also used directly by manual deploy and rollback).
func (engine *Engine) deployArtifact(ctx context.Context, cluster *k8s.Cluster, environment *store.EnvironmentFull, pipeline *store.PipelineView) error {
	namespace, applicationName := pipeline.Namespace, pipeline.ApplicationName

	runtimeSpec, _ := engine.Store.FindRuntimeSpec(ctx, namespace, applicationName)
	var environmentConfig *store.RuntimeEnvironmentConfig
	var healthCheck *store.HealthCheck
	if runtimeSpec != nil {
		for i := range runtimeSpec.EnvironmentConfigs {
			config := &runtimeSpec.EnvironmentConfigs[i]
			if config.EnvironmentName != nil && pipeline.Environment != nil && *config.EnvironmentName == *pipeline.Environment {
				environmentConfig = config
				break
			}
		}
		healthCheck = runtimeSpec.HealthCheck
	}
	if healthCheck == nil {
		healthCheck = store.DefaultRuntimeSpec(namespace, applicationName).HealthCheck
	}
	serviceConfig, _ := engine.Store.FindServiceConfig(ctx, namespace, applicationName)
	var expertEnvironmentConfig *store.ExpertEnvironmentConfig
	if expertConfig, err := engine.Store.FindExpertConfig(ctx, namespace, applicationName); err == nil {
		for i := range expertConfig.EnvironmentConfigs {
			config := &expertConfig.EnvironmentConfigs[i]
			if config.EnvironmentName != nil && pipeline.Environment != nil && *config.EnvironmentName == *pipeline.Environment {
				expertEnvironmentConfig = config
				break
			}
		}
	}
	domains, _ := engine.Store.ListDomainsFull(ctx)

	return Deploy(ctx, cluster, &deployInput{
		Pipeline:      pipeline,
		Namespace:     namespace,
		Application:   applicationName,
		Environment:   environment,
		RuntimeSpec:   environmentConfig,
		HealthCheck:   healthCheck,
		ServiceConfig: serviceConfig,
		ExpertConfig:  expertEnvironmentConfig,
		CertResolver:  engine.CertResolver,
		Domains:       domains,
	})
}

func (engine *Engine) scanRollingOutPipelines(ctx context.Context) {
	pipelines, err := engine.Store.FindPipelinesByStatus(ctx, domain.PipelineRollingOut)
	if err != nil {
		log.Printf("rollout scan: %v", err)
		return
	}
	for i := range pipelines {
		pipeline := &pipelines[i]
		if err := engine.scanOneRollingOut(ctx, pipeline); err != nil {
			log.Printf("pipeline %s rollout scan error: %v", pipeline.ID, err)
		}
	}
}

var fatalWaitingReasons = map[string]bool{
	"ImagePullBackOff": true, "ErrImagePull": true, "CrashLoopBackOff": true,
}

func (engine *Engine) scanOneRollingOut(ctx context.Context, pipeline *store.PipelineView) error {
	environmentName := ""
	if pipeline.Environment != nil {
		environmentName = *pipeline.Environment
	}
	cluster, _, err := engine.cluster(ctx, environmentName)
	if err != nil {
		return err
	}
	namespace, applicationName := pipeline.Namespace, pipeline.ApplicationName

	statefulSet, err := cluster.Clientset.AppsV1().StatefulSets(namespace).Get(ctx, applicationName, metav1.GetOptions{})
	if apierrors.IsNotFound(err) {
		return engine.failRollout(ctx, pipeline, "新版本部署失败：StatefulSet 不存在。")
	}
	if err != nil {
		return err
	}

	desired := int32(0)
	if statefulSet.Spec.Replicas != nil {
		desired = *statefulSet.Spec.Replicas
	}
	status := statefulSet.Status
	generationObserved := status.ObservedGeneration >= statefulSet.Generation
	rolloutComplete := generationObserved && status.UpdatedReplicas == desired && status.ReadyReplicas == desired

	pods, err := cluster.Clientset.CoreV1().Pods(namespace).List(ctx, metav1.ListOptions{
		LabelSelector: applicationTypeLabel + "=" + applicationTypeLabelValue + "," + applicationNameLabel + "=" + applicationName,
	})
	if err != nil {
		return err
	}
	failureReason := ""
	for _, pod := range pods.Items {
		for _, containerStatus := range pod.Status.ContainerStatuses {
			if waiting := containerStatus.State.Waiting; waiting != nil && fatalWaitingReasons[waiting.Reason] {
				failureReason = waiting.Reason + " (" + pod.Name + ")"
			}
		}
	}

	switch {
	case failureReason != "":
		return engine.failRollout(ctx, pipeline, "新版本部署失败："+failureReason)
	case rolloutComplete:
		updated, err := engine.Store.UpdatePipelineStatusIfMatch(ctx, pipeline.ID, domain.PipelineRollingOut, domain.PipelineSucceeded)
		if err == nil && updated > 0 {
			log.Printf("pipeline %s succeeded", pipeline.ID)
			engine.notifyPipeline(pipeline, "SUCCEEDED", "应用已经成功发布。")
		}
		return err
	default:
		startedAt := statefulSet.Annotations[rolloutStartedAtAnnotation]
		if startedAt != "" {
			if parsed, err := time.Parse(time.RFC3339, strings.TrimSpace(startedAt)); err == nil {
				if time.Since(parsed) > rolloutTimeout {
					return engine.failRollout(ctx, pipeline, "发布生效超时，新版本未在规定时间内就绪。")
				}
			}
		}
		return nil // still rolling out
	}
}

func (engine *Engine) failRollout(ctx context.Context, pipeline *store.PipelineView, message string) error {
	updated, err := engine.Store.UpdatePipelineStatusAndMessageIfMatch(ctx, pipeline.ID, domain.PipelineRollingOut, domain.PipelineError, message)
	if err == nil && updated > 0 {
		log.Printf("pipeline %s failed rollout: %s", pipeline.ID, message)
		engine.notifyPipeline(pipeline, "FAILED", message)
	}
	return err
}
