package engine

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"time"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"

	"github.com/wellch4n/oops/server/internal/cron"
)

// RunScheduledRestarts fires once per minute (on the minute) and rolling-
// restarts applications whose expert config cron matches, mirroring
// ScheduledRestartJob. No last-run state: a matched minute fires exactly once.
func (engine *Engine) RunScheduledRestarts(ctx context.Context) {
	go func() {
		for {
			now := time.Now()
			nextMinute := now.Truncate(time.Minute).Add(time.Minute)
			select {
			case <-ctx.Done():
				return
			case <-time.After(time.Until(nextMinute)):
				engine.scanScheduledRestarts(ctx, nextMinute)
			}
		}
	}()
}

func (engine *Engine) scanScheduledRestarts(ctx context.Context, minute time.Time) {
	configs, err := engine.Store.ListAllExpertConfigs(ctx)
	if err != nil {
		log.Printf("scheduled restart scan: %v", err)
		return
	}
	for _, config := range configs {
		for _, environmentConfig := range config.EnvironmentConfigs {
			if !environmentConfig.ScheduledRestartEnabled ||
				environmentConfig.ScheduledRestartCron == nil || *environmentConfig.ScheduledRestartCron == "" ||
				environmentConfig.EnvironmentName == nil {
				continue
			}
			schedule, err := cron.Parse(*environmentConfig.ScheduledRestartCron)
			if err != nil {
				continue
			}
			if !schedule.Next(minute.Add(-time.Second)).Equal(minute.Truncate(time.Minute)) {
				continue
			}
			namespace, application := config.Namespace, config.ApplicationName
			environmentName := *environmentConfig.EnvironmentName
			go func() {
				if err := engine.rolloutRestart(ctx, environmentName, namespace, application); err != nil {
					log.Printf("scheduled restart failed for %s/%s in %s: %v", namespace, application, environmentName, err)
				} else {
					log.Printf("triggered scheduled rolling restart for %s/%s in %s", namespace, application, environmentName)
				}
			}()
		}
	}
}

// rolloutRestart mirrors the gateway: stamp kubectl.kubernetes.io/restartedAt
// on the pod template, truncated to the minute so concurrent scans coincide.
func (engine *Engine) rolloutRestart(ctx context.Context, environmentName, namespace, applicationName string) error {
	cluster, _, err := engine.cluster(ctx, environmentName)
	if err != nil {
		return err
	}
	if _, err := cluster.Clientset.AppsV1().StatefulSets(namespace).Get(ctx, applicationName, metav1.GetOptions{}); err != nil {
		return nil // absent workload: nothing to restart, like the Java gateway
	}
	restartedAt := time.Now().Truncate(time.Minute).UTC().Format(time.RFC3339)
	patch, err := json.Marshal(map[string]any{
		"spec": map[string]any{
			"template": map[string]any{
				"metadata": map[string]any{
					"annotations": map[string]string{"kubectl.kubernetes.io/restartedAt": restartedAt},
				},
			},
		},
	})
	if err != nil {
		return err
	}
	_, err = cluster.Clientset.AppsV1().StatefulSets(namespace).
		Patch(ctx, applicationName, types.StrategicMergePatchType, patch, metav1.PatchOptions{})
	if err != nil {
		return fmt.Errorf("rollout restart: %w", err)
	}
	return nil
}

// RolloutRestartNow is the same restart used by expert-config updates.
func (engine *Engine) RolloutRestartNow(ctx context.Context, environmentName, namespace, applicationName string) error {
	return engine.rolloutRestart(ctx, environmentName, namespace, applicationName)
}
