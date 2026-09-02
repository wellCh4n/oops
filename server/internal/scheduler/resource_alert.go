package scheduler

import (
	"context"
	"fmt"
	"log/slog"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/wellch4n/oops/server/internal/config"
	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/prometheus"
	"github.com/wellch4n/oops/server/internal/service"
)

// ResourceAlert warns an application's owner when CPU or memory sits against
// its limit.
//
// The scan is three phases and only the middle one fans out: the targets and
// the previous state are each read in one query on the scan goroutine, the
// network-bound probes run concurrently touching no database, and the
// transitions are written back in one pass. That keeps the connection pool out
// of the concurrent half entirely.
type ResourceAlert struct {
	services *service.Services
}

func NewResourceAlert(services *service.Services) *ResourceAlert {
	return &ResourceAlert{services: services}
}

func (j *ResourceAlert) Name() string            { return "resource-alert" }
func (j *ResourceAlert) Interval() time.Duration { return minuteAligned }

// target is one application+environment+metric worth probing.
type target struct {
	namespace       string
	applicationName string
	environment     string
	metric          domain.AlertMetric
	// limit is the configured ceiling, in the metric's own unit (millicores or
	// bytes). An application without one is not a target at all.
	limit float64
}

func (t target) key() string {
	return strings.Join([]string{t.namespace, t.applicationName, t.environment, string(t.metric)}, "\n")
}

// probeResult is what one probe concluded. Failed is kept apart from "not
// firing": a failed probe must leave the stored state untouched, so an
// unreachable backend neither repeats an alert nor falsely resolves one.
type probeResult struct {
	target target
	pods   []string
	failed bool
}

func (j *ResourceAlert) Run(ctx context.Context) {
	alertConfig := j.services.Config.Metrics.Alert
	if !alertConfig.Enabled {
		return
	}
	targets, err := j.collectTargets(ctx)
	if err != nil {
		slog.Error("could not collect resource alert targets", "error", err)
		return
	}
	if len(targets) == 0 {
		return
	}
	states, err := j.loadStates(ctx)
	if err != nil {
		slog.Error("could not load resource alert state", "error", err)
		return
	}
	results := j.probeAll(ctx, targets, alertConfig)
	j.applyResults(ctx, results, states, alertConfig)
}

// collectTargets reads every application's limits in one query. An application
// with no limit for a metric is skipped: `/metrics/resource` carries no limits
// series, so there is nothing to take a percentage of.
func (j *ResourceAlert) collectTargets(ctx context.Context) ([]target, error) {
	specs, err := j.services.Store.Applications().FindAllRuntimeSpecs(ctx)
	if err != nil {
		return nil, err
	}
	var targets []target
	for _, spec := range specs {
		for _, environmentConfig := range spec.EnvironmentConfigs {
			environment := domain.Deref(environmentConfig.Environment)
			if environment == "" {
				continue
			}
			if cpuMillis := domain.QuantityToMillicores(environmentConfig.CPULimit); cpuMillis != nil && *cpuMillis > 0 {
				targets = append(targets, target{
					namespace: spec.Namespace, applicationName: spec.ApplicationName,
					environment: environment, metric: domain.AlertMetricCPU, limit: float64(*cpuMillis),
				})
			}
			if memoryBytes := domain.QuantityToBytes(environmentConfig.MemoryLimit); memoryBytes != nil && *memoryBytes > 0 {
				targets = append(targets, target{
					namespace: spec.Namespace, applicationName: spec.ApplicationName,
					environment: environment, metric: domain.AlertMetricMemory, limit: float64(*memoryBytes),
				})
			}
		}
	}
	return targets, nil
}

func (j *ResourceAlert) loadStates(ctx context.Context) (map[string]domain.AlertState, error) {
	stored, err := j.services.Store.AlertStates().FindAll(ctx)
	if err != nil {
		return nil, err
	}
	states := make(map[string]domain.AlertState, len(stored))
	for _, state := range stored {
		key := target{
			namespace: state.Namespace, applicationName: state.ApplicationName,
			environment: state.Environment, metric: state.Metric,
		}.key()
		states[key] = state
	}
	return states, nil
}

// probeAll runs one instant query per target, concurrently.
func (j *ResourceAlert) probeAll(ctx context.Context, targets []target, alertConfig config.MetricsAlert) []probeResult {
	results := make([]probeResult, len(targets))
	var group sync.WaitGroup
	for index := range targets {
		group.Add(1)
		go func(slot int) {
			defer group.Done()
			results[slot] = j.probe(ctx, targets[slot], alertConfig)
		}(index)
	}
	group.Wait()
	return results
}

// probe asks the backend which of an application's pods never dropped below the
// threshold for the sustained window.
//
// The whole "sustained for N minutes" condition lives in the PromQL, so a
// returned row *is* a pod that stayed over the line — OOPS keeps no time series
// and no rolling buffer of its own.
func (j *ResourceAlert) probe(ctx context.Context, item target, alertConfig config.MetricsAlert) probeResult {
	rule := alertConfig.CPU
	if item.metric == domain.AlertMetricMemory {
		rule = alertConfig.Memory
	}
	environment, err := j.services.Environments.FindByName(ctx, item.environment)
	if err != nil || environment == nil {
		return probeResult{target: item, failed: true}
	}
	selector, err := prometheus.PodSelector(item.namespace, item.applicationName)
	if err != nil {
		return probeResult{target: item, failed: true}
	}
	threshold := item.limit * float64(rule.ThresholdPercent) / 100
	interval := j.services.Config.Metrics.History.IntervalSeconds
	sustained := rule.SustainedMinutes * 60

	var usage string
	if item.metric == domain.AlertMetricCPU {
		usage = fmt.Sprintf("sum by (pod) (rate(container_cpu_usage_seconds_total{%s}[%ds])) * 1000", selector, interval*2)
	} else {
		usage = fmt.Sprintf("sum by (pod) (container_memory_working_set_bytes{%s})", selector)
	}
	query := fmt.Sprintf("min_over_time((%s)[%ds:%ds]) > %g", usage, sustained, interval, threshold)

	body, err := j.services.Prometheus.Query(ctx, environment, query)
	if err != nil {
		return probeResult{target: item, failed: true}
	}
	values, err := prometheus.ParseVector(body)
	if err != nil {
		return probeResult{target: item, failed: true}
	}
	pods := make([]string, 0, len(values))
	for pod := range values {
		pods = append(pods, pod)
	}
	sort.Strings(pods)
	return probeResult{target: item, pods: pods}
}

// applyResults turns probe outcomes into state transitions and notifications.
// The state row is what makes this edge-triggered: notify on OK->FIRING, repeat
// on the configured interval, and notify once on recovery.
func (j *ResourceAlert) applyResults(ctx context.Context, results []probeResult, states map[string]domain.AlertState, alertConfig config.MetricsAlert) {
	now := domain.Now()
	repeatAfter := time.Duration(alertConfig.RepeatIntervalMinutes) * time.Minute
	for _, result := range results {
		if result.failed {
			continue
		}
		key := result.target.key()
		previous, known := states[key]
		firing := len(result.pods) > 0

		switch {
		case firing && (!known || !previous.Firing):
			state := domain.AlertState{
				Namespace: result.target.namespace, ApplicationName: result.target.applicationName,
				Environment: result.target.environment, Metric: result.target.metric,
				Firing: true, FiringSince: now, LastNotifiedTime: now,
			}
			if known {
				state.ID = previous.ID
				state.CreatedTime = previous.CreatedTime
			}
			j.save(ctx, &state)
			j.notify(ctx, result, true)

		case firing && previous.Firing:
			// Already firing: repeat only when the interval has elapsed.
			if previous.LastNotifiedTime.Valid && time.Since(previous.LastNotifiedTime.Time) < repeatAfter {
				continue
			}
			previous.LastNotifiedTime = now
			j.save(ctx, &previous)
			j.notify(ctx, result, true)

		case !firing && known && previous.Firing:
			previous.Firing = false
			previous.LastNotifiedTime = now
			j.save(ctx, &previous)
			j.notify(ctx, result, false)
		}
	}
}

func (j *ResourceAlert) save(ctx context.Context, state *domain.AlertState) {
	if _, err := j.services.Store.AlertStates().Save(ctx, state); err != nil {
		slog.Error("could not record an alert state transition",
			"application", state.ApplicationName, "metric", state.Metric, "error", err)
	}
}

// notify sends one message per application listing the offending pods, rather
// than one message per pod.
func (j *ResourceAlert) notify(ctx context.Context, result probeResult, firing bool) {
	application, err := j.services.Store.Applications().FindRow(ctx, result.target.namespace, result.target.applicationName)
	if err != nil || application == nil {
		return
	}
	owner := domain.Deref(application.Owner)
	if owner == "" {
		return
	}
	metric := strings.ToLower(string(result.target.metric))
	if firing {
		title := fmt.Sprintf("%s · %s 使用率告警", result.target.applicationName, metric)
		body := fmt.Sprintf("环境 %s 中以下 Pod 的 %s 持续接近上限：%s",
			result.target.environment, metric, strings.Join(result.pods, ", "))
		j.services.Notifier.Notify(ctx, owner, title, body)
		return
	}
	j.services.Notifier.Notify(ctx, owner,
		fmt.Sprintf("%s · %s 使用率恢复", result.target.applicationName, metric),
		fmt.Sprintf("环境 %s 中的 %s 使用率已回落。", result.target.environment, metric))
}
