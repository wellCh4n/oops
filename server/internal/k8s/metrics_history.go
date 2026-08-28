package k8s

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"time"
)

// MetricsBackend locates the in-cluster Prometheus-compatible Service.
type MetricsBackend struct {
	Namespace   string
	ServiceName string
	Port        int
}

func (backend MetricsBackend) configured() bool {
	return backend.Namespace != "" && backend.ServiceName != "" && backend.Port > 0
}

func (backend MetricsBackend) describe() string {
	return fmt.Sprintf("%s/%s:%d", backend.Namespace, backend.ServiceName, backend.Port)
}

// MonitoringNotAvailable is the stable error code the charts recognise.
const MonitoringNotAvailable = "MONITORING_NOT_AVAILABLE"

type monitoringError struct{ message string }

func (e *monitoringError) Error() string { return e.message }

// IsMonitoringError reports a user-facing monitoring failure message.
func IsMonitoringError(err error) bool {
	_, matches := err.(*monitoringError)
	return matches
}

var safeMetricName = regexp.MustCompile(`^[a-z0-9]([-a-z0-9]*[a-z0-9])?$`)

// podSelector mirrors PrometheusSelectors.podSelector.
func podSelector(namespace, applicationName string) (string, error) {
	if !safeMetricName.MatchString(namespace) {
		return "", &monitoringError{message: "Invalid namespace: " + namespace}
	}
	if !safeMetricName.MatchString(applicationName) {
		return "", &monitoringError{message: "Invalid application name: " + applicationName}
	}
	return `namespace="` + namespace + `",pod=~"` + applicationName + `-[0-9]+",container!="",container!="POD"`, nil
}

// proxyPrometheus GETs a Prometheus API path through the API server's service
// proxy, mirroring ApiServerProxyPrometheusTransport.
func proxyPrometheus(ctx context.Context, cluster *Cluster, backend MetricsBackend, apiPath string) ([]byte, error) {
	result := cluster.Clientset.CoreV1().RESTClient().Get().
		RequestURI(fmt.Sprintf("/api/v1/namespaces/%s/services/%s:%d/proxy/api/v1/%s",
			backend.Namespace, backend.ServiceName, backend.Port, apiPath)).
		Do(ctx)
	statusCode := 0
	result.StatusCode(&statusCode)
	body, err := result.Raw()
	if statusCode == 404 {
		return nil, &monitoringError{message: MonitoringNotAvailable}
	}
	if err != nil {
		return nil, &monitoringError{message: fmt.Sprintf(
			"Cannot reach the monitoring backend at %s: %v", backend.describe(), err)}
	}
	return body, nil
}

// PodMetricPoint / PodMetricSeries / PodMetricHistoryResult mirror the DTOs.
type PodMetricPoint struct {
	Timestamp   int64 `json:"timestamp"`
	CPUMillis   int64 `json:"cpuMillis"`
	MemoryBytes int64 `json:"memoryBytes"`
}

type PodMetricSeries struct {
	PodName string           `json:"podName"`
	Points  []PodMetricPoint `json:"points"`
}

type PodMetricHistoryResult struct {
	IntervalSeconds int               `json:"intervalSeconds"`
	Aggregation     string            `json:"aggregation"`
	Series          []PodMetricSeries `json:"series"`
}

var rangePattern = regexp.MustCompile(`^(\d+)([mh])$`)

// QueryPodMetricHistory mirrors PodMetricHistoryService + the Prometheus
// provider: resolve the window and bucket on the step grid, run the two
// query_range calls, and zip the results per pod.
func QueryPodMetricHistory(ctx context.Context, cluster *Cluster, backend MetricsBackend,
	namespace, applicationName, rangeSpec, aggregation string, intervalSeconds, maxRangeHours int) (*PodMetricHistoryResult, error) {

	if !backend.configured() {
		return nil, &monitoringError{message: MonitoringNotAvailable}
	}
	if intervalSeconds < 1 {
		intervalSeconds = 1
	}
	window, err := parseMetricsRange(rangeSpec, maxRangeHours)
	if err != nil {
		return nil, err
	}
	mode := "avg"
	if aggregation == "max" {
		mode = "max"
	}

	const maxPointsPerSeries = 240
	idealSeconds := int64(window/time.Second) / maxPointsPerSeries
	interval := int64(intervalSeconds)
	intervalsPerBucket := (idealSeconds + interval - 1) / interval
	if intervalsPerBucket < 2 {
		intervalsPerBucket = 2
	}
	bucketSeconds := int(intervalsPerBucket * interval)

	bucketMillis := int64(bucketSeconds) * 1000
	end := time.Now().UnixMilli() / bucketMillis * bucketMillis
	start := (end - window.Milliseconds()) / bucketMillis * bucketMillis

	selector, err := podSelector(namespace, applicationName)
	if err != nil {
		return nil, err
	}
	rateWindow := max(2, 2*intervalSeconds)
	perPodRate := func(windowSeconds int) string {
		return fmt.Sprintf("sum by (pod) (rate(container_cpu_usage_seconds_total{%s}[%ds]))", selector, windowSeconds)
	}
	var cpuQuery string
	if mode == "max" {
		cpuQuery = fmt.Sprintf("max_over_time((%s)[%ds:%ds]) * 1000", perPodRate(rateWindow), bucketSeconds, intervalSeconds)
	} else {
		cpuQuery = perPodRate(bucketSeconds) + " * 1000"
	}
	reducer := "avg_over_time"
	if mode == "max" {
		reducer = "max_over_time"
	}
	memoryQuery := fmt.Sprintf("%s((sum by (pod) (container_memory_working_set_bytes{%s}))[%ds:%ds])",
		reducer, selector, bucketSeconds, intervalSeconds)

	cpu, err := fetchRange(ctx, cluster, backend, cpuQuery, start/1000, end/1000, bucketSeconds)
	if err != nil {
		return nil, err
	}
	memory, err := fetchRange(ctx, cluster, backend, memoryQuery, start/1000, end/1000, bucketSeconds)
	if err != nil {
		return nil, err
	}

	series := []PodMetricSeries{}
	for podName, cpuPoints := range cpu {
		memoryPoints, present := memory[podName]
		if !present {
			continue
		}
		memoryByTimestamp := map[int64]int64{}
		for _, point := range memoryPoints {
			memoryByTimestamp[int64(point[0])] = int64(point[1])
		}
		points := []PodMetricPoint{}
		for _, point := range cpuPoints {
			memoryBytes, matched := memoryByTimestamp[int64(point[0])]
			if !matched {
				continue
			}
			points = append(points, PodMetricPoint{
				Timestamp:   int64(point[0]) * 1000,
				CPUMillis:   int64(math.Round(point[1])),
				MemoryBytes: memoryBytes,
			})
		}
		if len(points) > 0 {
			series = append(series, PodMetricSeries{PodName: podName, Points: points})
		}
	}
	sort.Slice(series, func(i, j int) bool { return series[i].PodName < series[j].PodName })
	return &PodMetricHistoryResult{IntervalSeconds: bucketSeconds, Aggregation: mode, Series: series}, nil
}

func parseMetricsRange(rangeSpec string, maxRangeHours int) (time.Duration, error) {
	if rangeSpec == "" {
		return time.Hour, nil
	}
	matches := rangePattern.FindStringSubmatch(rangeSpec)
	if matches == nil {
		return 0, &monitoringError{message: "Unsupported range: " + rangeSpec}
	}
	amount, err := strconv.ParseInt(matches[1], 10, 64)
	if err != nil || amount <= 0 {
		return 0, &monitoringError{message: "Unsupported range: " + rangeSpec}
	}
	window := time.Duration(amount) * time.Minute
	if matches[2] == "h" {
		window = time.Duration(amount) * time.Hour
	}
	limit := time.Duration(maxRangeHours) * time.Hour
	if maxRangeHours > 0 && window > limit {
		window = limit
	}
	return window, nil
}

func fetchRange(ctx context.Context, cluster *Cluster, backend MetricsBackend, query string, startSeconds, endSeconds int64, stepSeconds int) (map[string][][2]float64, error) {
	apiPath := "query_range?query=" + url.QueryEscape(query) +
		"&start=" + strconv.FormatInt(startSeconds, 10) +
		"&end=" + strconv.FormatInt(endSeconds, 10) +
		"&step=" + strconv.Itoa(stepSeconds)
	body, err := proxyPrometheus(ctx, cluster, backend, apiPath)
	if err != nil {
		return nil, err
	}
	var response struct {
		Status string `json:"status"`
		Data   struct {
			Result []struct {
				Metric map[string]string `json:"metric"`
				Values [][2]any          `json:"values"`
			} `json:"result"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &response); err != nil {
		return nil, &monitoringError{message: "Cannot parse monitoring response: " + err.Error()}
	}
	byPod := map[string][][2]float64{}
	for _, result := range response.Data.Result {
		podName := result.Metric["pod"]
		if podName == "" {
			continue
		}
		points := [][2]float64{}
		for _, value := range result.Values {
			timestamp, _ := value[0].(float64)
			raw, _ := value[1].(string)
			reading, err := strconv.ParseFloat(raw, 64)
			if err != nil || math.IsNaN(reading) {
				continue
			}
			points = append(points, [2]float64{timestamp, reading})
		}
		if len(points) > 0 {
			byPod[podName] = points
		}
	}
	return byPod, nil
}

// PodAlertSelector exposes the shared PromQL matcher to the alert scan.
func PodAlertSelector(namespace, applicationName string) (string, error) {
	return podSelector(namespace, applicationName)
}

// ProxyPrometheusQuery exposes the API-server proxy transport for instant queries.
func ProxyPrometheusQuery(ctx context.Context, cluster *Cluster, backend MetricsBackend, apiPath string) ([]byte, error) {
	return proxyPrometheus(ctx, cluster, backend, apiPath)
}
