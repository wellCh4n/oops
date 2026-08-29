package engine

import (
	"context"
	"errors"
	"log/slog"
	"sort"
	"strings"

	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/util/retry"

	"github.com/wellch4n/oops/server/internal/store"
)

// defaultServiceAccount is what a pod falls back to once the expert config
// clears an explicit ServiceAccount, matching the Java gateway.
const defaultServiceAccount = "default"

// normalizedNodeNames renders a node list in the form change detection needs:
// blanks dropped, duplicates removed, order ignored. Nil and empty both mean
// "no node constraint", so reordering the same nodes is not a change.
func normalizedNodeNames(nodeNames []string) []string {
	seen := map[string]struct{}{}
	normalized := []string{}
	for _, name := range nodeNames {
		trimmed := strings.TrimSpace(name)
		if trimmed == "" {
			continue
		}
		if _, duplicate := seen[trimmed]; duplicate {
			continue
		}
		seen[trimmed] = struct{}{}
		normalized = append(normalized, trimmed)
	}
	sort.Strings(normalized)
	return normalized
}

func sameNodeNames(left, right []string) bool {
	leftNames, rightNames := normalizedNodeNames(left), normalizedNodeNames(right)
	if len(leftNames) != len(rightNames) {
		return false
	}
	for index := range leftNames {
		if leftNames[index] != rightNames[index] {
			return false
		}
	}
	return true
}

// expertConfigNeedsApply mirrors the Java service's change detection. The
// scheduled-restart fields are deliberately absent: they are read by the
// minute scan, not written onto the workload.
func expertConfigNeedsApply(config, existing *store.ExpertEnvironmentConfig) bool {
	var existingServiceAccount, existingPriority *string
	var existingNodeNames []string
	if existing != nil {
		existingServiceAccount, existingPriority = existing.ServiceAccountName, existing.Priority
		existingNodeNames = existing.NodeNames
	}
	if !sameOptionalText(config.ServiceAccountName, existingServiceAccount) {
		return true
	}
	// Compare the resolved class, so NORMAL, an unknown tier and no tier at
	// all all read as the same setting.
	newClass, _ := priorityClassNameOf(config.Priority)
	existingClass, _ := priorityClassNameOf(existingPriority)
	if newClass != existingClass {
		return true
	}
	return !sameNodeNames(config.NodeNames, existingNodeNames)
}

// ApplyExpertConfigNow pushes a just-saved expert config onto the running
// StatefulSet of every environment whose ServiceAccount, priority or node
// pinning changed, so the setting takes effect on save rather than waiting for
// the next publish. It mirrors ApplicationService.applyExpertConfigUpdates: an
// environment that cannot be reached is logged and skipped, never surfaced as
// a save failure, because the config itself is already stored.
func (engine *Engine) ApplyExpertConfigNow(ctx context.Context, namespace, applicationName string,
	configs, existingConfigs []store.ExpertEnvironmentConfig) {

	for i := range configs {
		config := &configs[i]
		if config.Environment == nil || *config.Environment == "" {
			continue
		}
		var existing *store.ExpertEnvironmentConfig
		for j := range existingConfigs {
			candidate := &existingConfigs[j]
			if candidate.Environment != nil && *candidate.Environment == *config.Environment {
				existing = candidate
				break
			}
		}
		if !expertConfigNeedsApply(config, existing) {
			continue
		}
		if err := engine.applyExpertConfig(ctx, *config.Environment, namespace, applicationName, config); err != nil {
			slog.Warn("failed to apply expert config",
				"namespace", namespace, "application", applicationName,
				"environment", *config.Environment, "error", err)
		}
	}
}

// applyExpertConfig writes the ServiceAccount, PriorityClass and node affinity
// straight onto the live StatefulSet. All three sit in the pod template, so
// unlike a pure rescale this does roll the pods — which is the point, and what
// the Java gateway did. A cleared setting is written back as its empty form so
// the pod actually loses the constraint.
func (engine *Engine) applyExpertConfig(ctx context.Context, environmentName, namespace, applicationName string,
	expertConfig *store.ExpertEnvironmentConfig) error {

	cluster, _, err := engine.cluster(ctx, environmentName)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return nil // the config names an environment that no longer exists
		}
		return err
	}
	statefulSets := cluster.Clientset.AppsV1().StatefulSets(namespace)
	if _, err := statefulSets.Get(ctx, applicationName, metav1.GetOptions{}); err != nil {
		if apierrors.IsNotFound(err) {
			return nil // never published to this environment: nothing to patch
		}
		return err
	}
	if err := ensurePriorityClass(ctx, cluster, expertConfig.Priority); err != nil {
		return err
	}
	serviceAccountName := defaultServiceAccount
	if expertConfig.ServiceAccountName != nil && *expertConfig.ServiceAccountName != "" {
		serviceAccountName = *expertConfig.ServiceAccountName
	}
	priorityClassName, _ := priorityClassNameOf(expertConfig.Priority)
	affinity := nodeAffinityFor(expertConfig.NodeNames)

	return retry.RetryOnConflict(retry.DefaultRetry, func() error {
		statefulSet, err := statefulSets.Get(ctx, applicationName, metav1.GetOptions{})
		if err != nil {
			if apierrors.IsNotFound(err) {
				return nil
			}
			return err
		}
		podSpec := &statefulSet.Spec.Template.Spec
		podSpec.ServiceAccountName = serviceAccountName
		// A blank class name clears the tier, letting the pod fall back to the
		// cluster default; a nil affinity lets it schedule freely again.
		podSpec.PriorityClassName = priorityClassName
		podSpec.Affinity = affinity
		_, err = statefulSets.Update(ctx, statefulSet, metav1.UpdateOptions{})
		return err
	})
}
