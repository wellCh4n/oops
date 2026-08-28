package engine

import (
	"context"
	"log/slog"
	"strings"

	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"github.com/wellch4n/oops/server/internal/k8s"
)

// MigrationResult mirrors NamespaceMigrationResult.
type MigrationResult struct {
	MigratedEnvironments []string `json:"migratedEnvironments"`
	FailedEnvironments   []string `json:"failedEnvironments"`
}

// MigrateNamespace mirrors NamespaceMigrationService.migrateNamespace: snapshot
// the live workloads, move the database rows, then recreate each running
// workload in the target namespace and delete the old one. Access checks are
// the caller's job (they belong to the HTTP layer's principal).
func (engine *Engine) MigrateNamespace(ctx context.Context, namespace, name, targetNamespace string) (*MigrationResult, error) {
	target := strings.TrimSpace(targetNamespace)
	if target == "" {
		return nil, bizf("Target namespace is required")
	}
	if target == namespace {
		return nil, bizf("Target namespace must be different from the current namespace")
	}
	if exists, _ := engine.Store.NamespaceRecordExists(ctx, target); !exists {
		return nil, bizf("Target namespace not found: %s", target)
	}
	if _, err := engine.Store.FindApplication(ctx, target, name); err == nil {
		return nil, bizf("An application named %s already exists in namespace %s", name, target)
	}
	if active, _ := engine.Store.HasActivePipeline(ctx, namespace, name); active {
		return nil, bizf("Application is being deployed")
	}

	// Phase 1: snapshot the live workload (image + config) of each bound environment.
	type migrationPlan struct {
		Environment  string
		Cluster      *k8s.Cluster
		CurrentImage string
		ConfigMaps   []k8s.ConfigMapItem
	}
	bindings, _ := engine.Store.ListEnvironmentBindings(ctx, namespace, name)
	result := &MigrationResult{MigratedEnvironments: []string{}, FailedEnvironments: []string{}}
	plans := []migrationPlan{}
	for _, binding := range bindings {
		cluster, _, err := engine.cluster(ctx, binding.Environment)
		if err != nil {
			result.FailedEnvironments = append(result.FailedEnvironments, binding.Environment)
			continue
		}
		image, err := k8s.FindCurrentImage(ctx, cluster, namespace, name)
		if err != nil {
			result.FailedEnvironments = append(result.FailedEnvironments, binding.Environment)
			continue
		}
		if image == nil || *image == "" {
			continue // bound but not running here; nothing to recreate
		}
		configMaps, err := k8s.GetConfigMaps(ctx, cluster, namespace, name)
		if err != nil {
			result.FailedEnvironments = append(result.FailedEnvironments, binding.Environment)
			continue
		}
		plans = append(plans, migrationPlan{
			Environment:  binding.Environment,
			Cluster:      cluster,
			CurrentImage: *image,
			ConfigMaps:   configMaps,
		})
	}

	// Phase 2: move the database rows.
	if err := engine.Store.MigrateApplicationNamespace(ctx, namespace, target, name); err != nil {
		return nil, err
	}

	// Phase 3: recreate each running workload in the target, then delete the old.
	for _, plan := range plans {
		err := engine.redeployToTarget(ctx, plan.Cluster, plan.ConfigMaps, plan.Environment, target, name, plan.CurrentImage)
		if err != nil {
			slog.Error("failed to migrate workload", "namespace", namespace, "application", name, "environment", plan.Environment, "error", err)
			result.FailedEnvironments = append(result.FailedEnvironments, plan.Environment)
			continue
		}
		if err := DeleteWorkload(ctx, plan.Cluster, namespace, name); err != nil {
			slog.Error("failed to delete old workload", "namespace", namespace, "application", name, "environment", plan.Environment, "error", err)
		}
		result.MigratedEnvironments = append(result.MigratedEnvironments, plan.Environment)
	}
	return result, nil
}

func (engine *Engine) redeployToTarget(ctx context.Context, cluster *k8s.Cluster, configMaps []k8s.ConfigMapItem,
	environmentName, target, name, currentImage string) error {

	commands := make([]k8s.UpdateConfigMapRequest, 0, len(configMaps))
	for _, item := range configMaps {
		commands = append(commands, k8s.UpdateConfigMapRequest{
			Key: item.Key, Value: item.Value, Secret: item.Secret,
			MountPath: item.MountPath, Group: item.Group, Comment: item.Comment,
		})
	}
	// Config first so new pods start with their environment present.
	if len(commands) > 0 {
		if err := k8s.UpdateConfigMaps(ctx, cluster, target, name, commands); err != nil {
			return err
		}
	}
	if err := engine.DeployImageTo(ctx, target, name, environmentName, currentImage); err != nil {
		return err
	}
	// Re-apply so the config inherits the StatefulSet owner reference.
	if len(commands) > 0 {
		if err := k8s.UpdateConfigMaps(ctx, cluster, target, name, commands); err != nil {
			return err
		}
	}
	return nil
}

// DeleteWorkload mirrors the gateway: only the StatefulSet is deleted; Service
// and IngressRoute cascade via ownerReference.
func DeleteWorkload(ctx context.Context, cluster *k8s.Cluster, namespace, applicationName string) error {
	err := cluster.Clientset.AppsV1().StatefulSets(namespace).Delete(ctx, applicationName, metav1.DeleteOptions{})
	if apierrors.IsNotFound(err) {
		return nil
	}
	return err
}
