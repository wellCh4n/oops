package engine

import (
	"context"
	"errors"
	"log/slog"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/util/retry"

	"github.com/wellch4n/oops/server/internal/store"
)

// runtimeResourceRequirements turns a runtime spec's four quantity strings into
// a container resource block; memory carries the Mi suffix the form leaves off.
// The second result reports whether the spec asked for anything at all, which
// is what tells the caller a resource change is worth pushing.
func runtimeResourceRequirements(runtimeSpec *store.RuntimeEnvironmentConfig) (corev1.ResourceRequirements, bool) {
	requests, limits := corev1.ResourceList{}, corev1.ResourceList{}
	setQuantity := func(list corev1.ResourceList, name corev1.ResourceName, value *string, suffix string) {
		if value == nil || *value == "" {
			return
		}
		quantity, err := resource.ParseQuantity(*value + suffix)
		if err != nil {
			return
		}
		list[name] = quantity
	}
	setQuantity(requests, corev1.ResourceCPU, runtimeSpec.CPURequest, "")
	setQuantity(limits, corev1.ResourceCPU, runtimeSpec.CPULimit, "")
	setQuantity(requests, corev1.ResourceMemory, runtimeSpec.MemoryRequest, "Mi")
	setQuantity(limits, corev1.ResourceMemory, runtimeSpec.MemoryLimit, "Mi")
	if len(requests) == 0 && len(limits) == 0 {
		return corev1.ResourceRequirements{}, false
	}
	return corev1.ResourceRequirements{Requests: requests, Limits: limits}, true
}

// sameOptionalText compares two optional strings, reading a missing value and
// a blank one as the same thing: the form posts "" for a field the stored
// config simply never had, and that is not a change worth a cluster call.
func sameOptionalText(left, right *string) bool {
	leftText, rightText := "", ""
	if left != nil {
		leftText = *left
	}
	if right != nil {
		rightText = *right
	}
	return leftText == rightText
}

// runtimeSpecNeedsApply mirrors the Java service's change detection: a replica
// count the save actually carries and that differs from the stored one, or any
// changed resource quantity.
func runtimeSpecNeedsApply(config, existing *store.RuntimeEnvironmentConfig) bool {
	var existingReplicas *int
	var existingCPURequest, existingCPULimit, existingMemoryRequest, existingMemoryLimit *string
	if existing != nil {
		existingReplicas = existing.Replicas
		existingCPURequest, existingCPULimit = existing.CPURequest, existing.CPULimit
		existingMemoryRequest, existingMemoryLimit = existing.MemoryRequest, existing.MemoryLimit
	}
	replicasChanged := config.Replicas != nil &&
		(existingReplicas == nil || *existingReplicas != *config.Replicas)
	resourcesChanged := !sameOptionalText(config.CPURequest, existingCPURequest) ||
		!sameOptionalText(config.CPULimit, existingCPULimit) ||
		!sameOptionalText(config.MemoryRequest, existingMemoryRequest) ||
		!sameOptionalText(config.MemoryLimit, existingMemoryLimit)
	return replicasChanged || resourcesChanged
}

// ApplyRuntimeSpecNow pushes a just-saved runtime spec onto the running
// StatefulSet of every environment whose replicas or resources changed, so
// scaling takes effect on save rather than waiting for the next publish. It
// mirrors ApplicationService.applyRuntimeSpecEnvironmentConfigUpdates: an
// environment that cannot be reached is logged and skipped, never surfaced as
// a save failure, because the spec itself is already stored.
func (engine *Engine) ApplyRuntimeSpecNow(ctx context.Context, namespace, applicationName string,
	configs, existingConfigs []store.RuntimeEnvironmentConfig) {

	for i := range configs {
		config := &configs[i]
		if config.Environment == nil || *config.Environment == "" {
			continue
		}
		var existing *store.RuntimeEnvironmentConfig
		for j := range existingConfigs {
			candidate := &existingConfigs[j]
			if candidate.Environment != nil && *candidate.Environment == *config.Environment {
				existing = candidate
				break
			}
		}
		if !runtimeSpecNeedsApply(config, existing) {
			continue
		}
		if err := engine.applyRuntimeSpec(ctx, *config.Environment, namespace, applicationName, config); err != nil {
			slog.Warn("failed to apply runtime spec",
				"namespace", namespace, "application", applicationName,
				"environment", *config.Environment, "error", err)
		}
	}
}

// applyRuntimeSpec writes the replica count and container resources straight
// onto the live StatefulSet. Both land in one update: rewriting resources that
// already match leaves the object untouched, so a pure scale does not restart
// any pod. An application never published to this environment has no workload
// to patch, which is a no-op rather than an error.
func (engine *Engine) applyRuntimeSpec(ctx context.Context, environmentName, namespace, applicationName string,
	runtimeSpec *store.RuntimeEnvironmentConfig) error {

	resources, hasResources := runtimeResourceRequirements(runtimeSpec)
	if runtimeSpec.Replicas == nil && !hasResources {
		return nil
	}
	cluster, _, err := engine.cluster(ctx, environmentName)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return nil // the config names an environment that no longer exists
		}
		return err
	}
	statefulSets := cluster.Clientset.AppsV1().StatefulSets(namespace)
	return retry.RetryOnConflict(retry.DefaultRetry, func() error {
		statefulSet, err := statefulSets.Get(ctx, applicationName, metav1.GetOptions{})
		if err != nil {
			if apierrors.IsNotFound(err) {
				return nil
			}
			return err
		}
		if runtimeSpec.Replicas != nil {
			replicas := int32(*runtimeSpec.Replicas)
			statefulSet.Spec.Replicas = &replicas
		}
		if hasResources {
			for index := range statefulSet.Spec.Template.Spec.Containers {
				statefulSet.Spec.Template.Spec.Containers[index].Resources = resources
			}
		}
		_, err = statefulSets.Update(ctx, statefulSet, metav1.UpdateOptions{})
		return err
	})
}
