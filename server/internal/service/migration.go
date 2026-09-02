package service

import (
	"context"
	"log/slog"
	"strings"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/k8s"
)

// environmentMigrationPlan is one environment's live workload, snapshotted in
// the old namespace before any row moves.
type environmentMigrationPlan struct {
	environmentName string
	environment     *domain.Environment
	currentImage    string
	configMaps      []k8s.ConfigMapItem
}

// MigrateNamespace moves an application to another namespace.
//
// The order matters and is the whole design: the live workloads are read first
// (phase 1), then every database row moves at once (phase 2), then each workload
// is recreated in the target namespace and the old one deleted (phase 3). Doing
// the reads first means a cluster that has gone away costs one environment, not
// the migration; doing the rows in the middle means the recreate already sees
// the application at its new address.
func (s *ApplicationService) MigrateNamespace(ctx context.Context, namespace, name, targetNamespace, currentUserID string) (*NamespaceMigrationResult, error) {
	target := strings.TrimSpace(targetNamespace)
	if target == "" {
		return nil, domain.Biz("Target namespace is required")
	}
	if target == namespace {
		return nil, domain.Biz("Target namespace must be different from the current namespace")
	}
	application, err := s.requireAggregate(ctx, namespace, name)
	if err != nil {
		return nil, err
	}
	operator, err := s.services.operator(ctx, currentUserID)
	if err != nil {
		return nil, err
	}
	if (operator == nil || !operator.IsAdmin()) && currentUserID != domain.Deref(application.Owner) {
		return nil, domain.Biz("Permission denied")
	}
	existingNamespace, err := s.services.Store.Namespaces().FindByName(ctx, target)
	if err != nil {
		return nil, err
	}
	if existingNamespace == nil {
		return nil, domain.Bizf("Target namespace not found: %s", target)
	}
	clash, err := s.repo().FindRow(ctx, target, name)
	if err != nil {
		return nil, err
	}
	if clash != nil {
		return nil, domain.Bizf("An application named %s already exists in namespace %s", name, target)
	}
	if err := s.services.Pipelines.ensureNoActivePipeline(ctx, namespace, name); err != nil {
		return nil, err
	}

	migrated := []string{}
	failed := []string{}

	// Phase 1 — snapshot what is actually running, in the old namespace.
	var plans []environmentMigrationPlan
	for _, binding := range application.Environments {
		environment, err := s.services.Store.Environments().FindByName(ctx, binding.Environment)
		if err != nil {
			return nil, err
		}
		if environment == nil {
			continue
		}
		currentImage, err := s.services.Runtime.FindCurrentImage(ctx, environment, namespace, name)
		if err != nil {
			slog.Error("could not read the live workload before a namespace migration",
				"namespace", namespace, "application", name, "environment", binding.Environment, "error", err)
			failed = append(failed, binding.Environment)
			continue
		}
		if strings.TrimSpace(currentImage) == "" {
			// Bound but not running here, so there is nothing to recreate.
			continue
		}
		configMaps, err := s.services.Configs.GetConfigMaps(ctx, environment, namespace, name)
		if err != nil {
			slog.Error("could not read the configuration before a namespace migration",
				"namespace", namespace, "application", name, "environment", binding.Environment, "error", err)
			failed = append(failed, binding.Environment)
			continue
		}
		plans = append(plans, environmentMigrationPlan{
			environmentName: binding.Environment, environment: environment,
			currentImage: currentImage, configMaps: configMaps,
		})
	}

	// Phase 2 — move the rows.
	if err := s.repo().MigrateNamespace(ctx, namespace, target, name); err != nil {
		return nil, err
	}
	if _, err := s.services.Store.Pipelines().MigrateNamespace(ctx, namespace, target, name); err != nil {
		return nil, err
	}
	if _, err := s.services.Store.AlertStates().MigrateNamespace(ctx, namespace, target, name); err != nil {
		return nil, err
	}
	moved, err := s.repo().FindAggregate(ctx, target, name)
	if err != nil {
		return nil, err
	}

	// Phase 3 — recreate each workload at the new address, then remove the old.
	for _, plan := range plans {
		if err := s.redeployToTarget(ctx, moved, plan, target, currentUserID); err != nil {
			slog.Error("could not recreate a workload in the target namespace",
				"application", name, "environment", plan.environmentName, "target", target, "error", err)
			failed = append(failed, plan.environmentName)
			continue
		}
		if err := s.services.Runtime.DeleteWorkload(ctx, plan.environment, namespace, name); err != nil {
			slog.Error("could not delete the old workload after a namespace migration",
				"namespace", namespace, "application", name, "environment", plan.environmentName, "error", err)
			failed = append(failed, plan.environmentName)
			continue
		}
		migrated = append(migrated, plan.environmentName)
	}

	return &NamespaceMigrationResult{
		SourceNamespace: namespace, TargetNamespace: target,
		MigratedEnvironments: migrated, FailedEnvironments: failed,
	}, nil
}

// redeployToTarget recreates one environment's workload in the new namespace,
// reusing the image it was already running rather than rebuilding it.
func (s *ApplicationService) redeployToTarget(ctx context.Context, application *domain.Application,
	plan environmentMigrationPlan, target, operatorUserID string) error {
	commands := configCommandsOf(plan.configMaps)
	// The configuration is written before the deploy so the first pods start
	// with it present, and again afterwards so it picks up the new
	// StatefulSet's owner reference and is cascade-deleted with it.
	if len(commands) > 0 {
		if err := s.services.Configs.UpdateConfigMaps(ctx, plan.environment, target, application.Name, commands); err != nil {
			return err
		}
	}
	pipeline := domain.InitializePipeline(target, application.Name, plan.environmentName,
		application.SourceType(), domain.Ptr(domain.DeployImmediate), operatorUserID)
	pipeline.Artifact = &plan.currentImage
	domains, err := s.services.Store.Domains().FindAll(ctx)
	if err != nil {
		return err
	}
	if err := k8s.Deploy(ctx, k8s.DeployInput{
		Pipeline:      pipeline,
		Application:   application,
		Environment:   plan.environment,
		RuntimeSpec:   application.RuntimeEnvironmentConfigOrDefault(plan.environmentName),
		HealthCheck:   application.HealthCheckOrDefault(),
		ServiceConfig: application.ServiceConfigOrDefault(),
		ExpertConfig:  application.ExpertEnvironmentConfigOrDefault(plan.environmentName),
		Domains:       domains,
		CertResolver:  s.services.Config.Ingress.CertResolver,
	}); err != nil {
		return err
	}
	if len(commands) > 0 {
		return s.services.Configs.UpdateConfigMaps(ctx, plan.environment, target, application.Name, commands)
	}
	return nil
}

// configCommandsOf turns a snapshot back into a write. The snapshot is already
// in display order and the writer re-derives each item's order from its position
// here, so iterating in order is what carries the manual ordering across.
func configCommandsOf(items []k8s.ConfigMapItem) []k8s.ConfigMapCommand {
	commands := make([]k8s.ConfigMapCommand, 0, len(items))
	for _, item := range items {
		commands = append(commands, k8s.ConfigMapCommand{
			Key: item.Key, Value: item.Value, Secret: item.Secret,
			MountPath: item.MountPath, Group: item.Group, Comment: item.Comment,
		})
	}
	return commands
}
