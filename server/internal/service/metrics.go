package service

import (
	"context"
	"fmt"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/prometheus"
)

// maxPointsPerSeries caps how many points a chart is asked to draw; the bucket
// width is derived from it rather than configured.
const maxPointsPerSeries = 240

var rangePattern = regexp.MustCompile(`^(\d+)([mh])$`)

// PodMetricPoint is one charted reading. The timestamp is epoch milliseconds.
type PodMetricPoint struct {
	Timestamp   int64 `json:"timestamp"`
	CPUMillis   int64 `json:"cpuMillis"`
	MemoryBytes int64 `json:"memoryBytes"`
}

// PodMetricSeries is one pod's line on the chart.
type PodMetricSeries struct {
	PodName string           `json:"podName"`
	Points  []PodMetricPoint `json:"points"`
}

// PodMetricHistory is the usage chart's whole payload.
type PodMetricHistory struct {
	IntervalSeconds int               `json:"intervalSeconds"`
	Aggregation     string            `json:"aggregation"`
	Series          []PodMetricSeries `json:"series"`
}

// MetricsHistory answers the usage charts.
func (s *ApplicationService) MetricsHistory(ctx context.Context, namespace, applicationName, environmentName, rangeText, aggregation string) (*PodMetricHistory, error) {
	// The environment is checked first: an unknown one would otherwise miss
	// every series key and return an empty chart, which looks exactly like
	// "no monitoring backend" and like "this application has no pods".
	environment, err := s.services.environmentByName(ctx, environmentName)
	if err != nil {
		return nil, err
	}
	window, err := s.parseRange(rangeText)
	if err != nil {
		return nil, err
	}
	mode, err := parseAggregation(aggregation)
	if err != nil {
		return nil, err
	}
	bucketSeconds := s.bucketSeconds(window)

	// Both ends are snapped down to the bucket grid. Backends evaluate a range
	// query at start, start+step, start+2*step…, so an unaligned start would
	// shift every point by a few seconds on each refresh and make the chart
	// jitter.
	bucket := time.Duration(bucketSeconds) * time.Second
	end := time.Now().Truncate(bucket)
	start := end.Add(-window).Truncate(bucket)

	selector, err := prometheus.PodSelector(namespace, applicationName)
	if err != nil {
		return nil, err
	}
	interval := s.services.Config.Metrics.History.IntervalSeconds

	cpu, err := s.queryMatrix(ctx, environment, cpuQuery(selector, bucketSeconds, interval, mode), start, end, bucketSeconds)
	if err != nil {
		return nil, err
	}
	memory, err := s.queryMatrix(ctx, environment, memoryQuery(selector, bucketSeconds, interval, mode), start, end, bucketSeconds)
	if err != nil {
		return nil, err
	}
	return &PodMetricHistory{
		IntervalSeconds: bucketSeconds,
		Aggregation:     strings.ToLower(mode),
		Series:          mergeSeries(cpu, memory),
	}, nil
}

func (s *ApplicationService) queryMatrix(ctx context.Context, environment *domain.Environment, query string, start, end time.Time, stepSeconds int) (map[string][]prometheus.Sample, error) {
	body, err := s.services.Prometheus.QueryRange(ctx, environment, query, start.Unix(), end.Unix(), stepSeconds)
	if err != nil {
		return nil, err
	}
	return prometheus.ParseMatrix(body)
}

// cpuQuery renders CPU as millicores. A counter has to be rated first, so MAX
// takes the maximum of the per-interval rate rather than of the raw counter.
func cpuQuery(selector string, stepSeconds, intervalSeconds int, aggregation string) string {
	rateWindow := max(2, 2*intervalSeconds)
	if aggregation == "MAX" {
		inner := fmt.Sprintf("sum by (pod) (rate(container_cpu_usage_seconds_total{%s}[%ds]))", selector, rateWindow)
		return fmt.Sprintf("max_over_time((%s)[%ds:%ds]) * 1000", inner, stepSeconds, intervalSeconds)
	}
	return fmt.Sprintf("sum by (pod) (rate(container_cpu_usage_seconds_total{%s}[%ds])) * 1000", selector, stepSeconds)
}

// memoryQuery reduces the bucket with a subquery, because memory is a gauge.
func memoryQuery(selector string, stepSeconds, intervalSeconds int, aggregation string) string {
	reducer := "avg_over_time"
	if aggregation == "MAX" {
		reducer = "max_over_time"
	}
	return fmt.Sprintf("%s((sum by (pod) (container_memory_working_set_bytes{%s}))[%ds:%ds])",
		reducer, selector, stepSeconds, intervalSeconds)
}

// bucketSeconds widens the bucket until the window fits in maxPointsPerSeries,
// always as a whole number of scrape intervals so each bucket holds whole
// samples.
func (s *ApplicationService) bucketSeconds(window time.Duration) int {
	interval := max(1, s.services.Config.Metrics.History.IntervalSeconds)
	ideal := int(window.Seconds()) / maxPointsPerSeries
	intervalsPerBucket := max(2, (ideal+interval-1)/interval)
	return intervalsPerBucket * interval
}

// parseRange reads "30m" or "6h", clamped to the configured maximum.
func (s *ApplicationService) parseRange(rangeText string) (time.Duration, error) {
	if strings.TrimSpace(rangeText) == "" {
		return time.Hour, nil
	}
	match := rangePattern.FindStringSubmatch(strings.ToLower(strings.TrimSpace(rangeText)))
	if match == nil {
		return 0, domain.Bizf("Unsupported range: %s", rangeText)
	}
	amount, err := strconv.Atoi(match[1])
	if err != nil || amount <= 0 {
		return 0, domain.Bizf("Unsupported range: %s", rangeText)
	}
	window := time.Duration(amount) * time.Minute
	if match[2] == "h" {
		window = time.Duration(amount) * time.Hour
	}
	maxWindow := time.Duration(s.services.Config.Metrics.History.MaxRangeHours) * time.Hour
	if window > maxWindow {
		return maxWindow, nil
	}
	return window, nil
}

func parseAggregation(value string) (string, error) {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "", "avg":
		return "AVG", nil
	case "max":
		return "MAX", nil
	default:
		return "", domain.Bizf("Unsupported aggregation: %s", value)
	}
}

// mergeSeries pairs each pod's CPU and memory readings by timestamp. A point
// present in only one of the two is dropped: a chart row needs both.
func mergeSeries(cpu, memory map[string][]prometheus.Sample) []PodMetricSeries {
	series := make([]PodMetricSeries, 0, len(cpu))
	for podName, cpuSamples := range cpu {
		memorySamples, found := memory[podName]
		if !found {
			continue
		}
		memoryByTimestamp := make(map[int64]float64, len(memorySamples))
		for _, sample := range memorySamples {
			memoryByTimestamp[sample.TimestampSeconds] = sample.Value
		}
		points := make([]PodMetricPoint, 0, len(cpuSamples))
		for _, sample := range cpuSamples {
			memoryBytes, found := memoryByTimestamp[sample.TimestampSeconds]
			if !found {
				continue
			}
			points = append(points, PodMetricPoint{
				Timestamp:   sample.TimestampSeconds * 1000,
				CPUMillis:   int64(sample.Value + 0.5),
				MemoryBytes: int64(memoryBytes),
			})
		}
		if len(points) > 0 {
			series = append(series, PodMetricSeries{PodName: podName, Points: points})
		}
	}
	sort.Slice(series, func(i, j int) bool { return series[i].PodName < series[j].PodName })
	return series
}
