package engine

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/wellch4n/oops/server/internal/k8s"
)

// AlertConfig mirrors oops.metrics.alert (ResourceAlertProperties).
type AlertConfig struct {
	Enabled               bool
	CPUThresholdPercent   int
	CPUSustainedMinutes   int
	MemThresholdPercent   int
	MemSustainedMinutes   int
	RepeatIntervalMinutes int
	IntervalSeconds       int
	Backend               k8s.MetricsBackend
}

type alertTarget struct {
	Namespace       string
	ApplicationName string
	Environment     string
	Metric          string // CPU | MEMORY
	Threshold       int64
	Window          time.Duration
}

// RunResourceAlerts starts the minute-aligned alert scan; a no-op unless
// enabled, mirroring the @ConditionalOnProperty gate.
func (engine *Engine) RunResourceAlerts(ctx context.Context, config AlertConfig) {
	if !config.Enabled {
		return
	}
	go func() {
		ticker := time.NewTicker(time.Minute)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				engine.scanResourceAlerts(ctx, config)
			}
		}
	}()
}

func parseCPUMillis(value string) (int64, bool) {
	value = strings.TrimSpace(value)
	if value == "" {
		return 0, false
	}
	if millis, isMillis := strings.CutSuffix(value, "m"); isMillis {
		parsed, err := strconv.ParseInt(millis, 10, 64)
		return parsed, err == nil
	}
	cores, err := strconv.ParseFloat(value, 64)
	if err != nil {
		return 0, false
	}
	return int64(cores * 1000), true
}

func parseMemoryBytes(value string) (int64, bool) {
	value = strings.TrimSpace(value)
	if value == "" {
		return 0, false
	}
	// Runtime spec memory values are plain Mi numbers on the write path.
	mebibytes, err := strconv.ParseFloat(strings.TrimSuffix(value, "Mi"), 64)
	if err != nil {
		return 0, false
	}
	return int64(mebibytes * 1024 * 1024), true
}

func (engine *Engine) collectAlertTargets(ctx context.Context, config AlertConfig) ([]alertTarget, error) {
	specs, err := engine.Store.ListAllRuntimeSpecs(ctx)
	if err != nil {
		return nil, err
	}
	targets := []alertTarget{}
	for _, spec := range specs {
		for _, environmentConfig := range spec.EnvironmentConfigs {
			if environmentConfig.Environment == nil {
				continue
			}
			if environmentConfig.CPULimit != nil {
				if limit, valid := parseCPUMillis(*environmentConfig.CPULimit); valid && limit > 0 {
					targets = append(targets, alertTarget{
						Namespace: spec.Namespace, ApplicationName: spec.ApplicationName,
						Environment: *environmentConfig.Environment, Metric: "CPU",
						Threshold: limit * int64(config.CPUThresholdPercent) / 100,
						Window:    time.Duration(config.CPUSustainedMinutes) * time.Minute,
					})
				}
			}
			if environmentConfig.MemoryLimit != nil {
				if limit, valid := parseMemoryBytes(*environmentConfig.MemoryLimit); valid && limit > 0 {
					targets = append(targets, alertTarget{
						Namespace: spec.Namespace, ApplicationName: spec.ApplicationName,
						Environment: *environmentConfig.Environment, Metric: "MEMORY",
						Threshold: limit * int64(config.MemThresholdPercent) / 100,
						Window:    time.Duration(config.MemSustainedMinutes) * time.Minute,
					})
				}
			}
		}
	}
	return targets, nil
}

// probeAlert runs one instant query; a returned pod never dropped below the
// threshold in the window, mirroring PrometheusResourceAlertProbe.
func (engine *Engine) probeAlert(ctx context.Context, config AlertConfig, target alertTarget) ([]string, error) {
	cluster, _, err := engine.cluster(ctx, target.Environment)
	if err != nil {
		return nil, err
	}
	selector, err := k8s.PodAlertSelector(target.Namespace, target.ApplicationName)
	if err != nil {
		return nil, err
	}
	interval := max(1, config.IntervalSeconds)
	windowSeconds := max(int64(interval)*2, int64(target.Window/time.Second))
	perPod := fmt.Sprintf("sum by (pod) (container_memory_working_set_bytes{%s})", selector)
	if target.Metric == "CPU" {
		perPod = fmt.Sprintf("sum by (pod) (rate(container_cpu_usage_seconds_total{%s}[%ds])) * 1000", selector, 2*interval)
	}
	query := fmt.Sprintf("min_over_time((%s)[%ds:%ds]) > %d", perPod, windowSeconds, interval, target.Threshold)
	body, err := k8s.ProxyPrometheusQuery(ctx, cluster, config.Backend, "query?query="+url.QueryEscape(query))
	if err != nil {
		return nil, err
	}
	var response struct {
		Data struct {
			Result []struct {
				Metric map[string]string `json:"metric"`
			} `json:"result"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &response); err != nil {
		return nil, err
	}
	pods := []string{}
	for _, result := range response.Data.Result {
		if pod := result.Metric["pod"]; pod != "" {
			pods = append(pods, pod)
		}
	}
	return pods, nil
}

func (engine *Engine) scanResourceAlerts(ctx context.Context, config AlertConfig) {
	targets, err := engine.collectAlertTargets(ctx, config)
	if err != nil {
		slog.Error("alert scan failed", "error", err)
		return
	}
	repeatInterval := time.Duration(config.RepeatIntervalMinutes) * time.Minute
	for _, target := range targets {
		pods, err := engine.probeAlert(ctx, config, target)
		if err != nil {
			// A failed probe leaves the state untouched: an unreachable
			// backend must neither repeat nor falsely resolve.
			continue
		}
		firing := len(pods) > 0
		state, stateErr := engine.Store.FindAlertState(ctx, target.Namespace, target.ApplicationName, target.Environment, target.Metric)
		wasFiring := stateErr == nil && state.Firing
		now := time.Now()
		switch {
		case firing && !wasFiring:
			if err := engine.Store.SaveAlertState(ctx, target.Namespace, target.ApplicationName, target.Environment, target.Metric, true); err == nil {
				engine.notifyAlert(target, pods, "FIRING")
			}
		case firing && wasFiring:
			if state.LastNotified == nil || now.Sub(*state.LastNotified) >= repeatInterval {
				if err := engine.Store.SaveAlertState(ctx, target.Namespace, target.ApplicationName, target.Environment, target.Metric, true); err == nil {
					engine.notifyAlert(target, pods, "FIRING")
				}
			}
		case !firing && wasFiring:
			if err := engine.Store.SaveAlertState(ctx, target.Namespace, target.ApplicationName, target.Environment, target.Metric, false); err == nil {
				engine.notifyAlert(target, nil, "RESOLVED")
			}
		}
	}
}

// notifyAlert delivers one message per application to the owner, like
// ApplicationAlertListener.
func (engine *Engine) notifyAlert(target alertTarget, pods []string, alertType string) {
	if engine.Notifier == nil {
		return
	}
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		application, err := engine.Store.FindApplication(ctx, target.Namespace, target.ApplicationName)
		if err != nil || application.Owner == nil || *application.Owner == "" {
			return
		}
		metricLabel := "内存"
		if target.Metric == "CPU" {
			metricLabel = "CPU"
		}
		title := "Oops 资源告警｜" + metricLabel + "持续超限"
		text := fmt.Sprintf("**应用**：%s/%s\n**环境**：%s\n**Pod**：%s",
			target.Namespace, target.ApplicationName, target.Environment, strings.Join(pods, ", "))
		if alertType == "RESOLVED" {
			title = "Oops 资源告警｜" + metricLabel + "已恢复"
			text = fmt.Sprintf("**应用**：%s/%s\n**环境**：%s", target.Namespace, target.ApplicationName, target.Environment)
		}
		if err := engine.Notifier.SendToUser(ctx, *application.Owner, title, text); err != nil {
			slog.Error("failed to send alert notification", "namespace", target.Namespace, "application", target.ApplicationName, "error", err)
		}
	}()
}
