package k8s

import (
	"context"
	"log/slog"
	"sort"

	"github.com/wellch4n/oops/server/internal/domain"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	metricsv1beta1 "k8s.io/metrics/pkg/apis/metrics/v1beta1"
)

// PodMetricSnapshot is one pod's live usage from metrics-server.
type PodMetricSnapshot struct {
	PodName     string `json:"podName"`
	CPUMillis   int64  `json:"cpuMillis"`
	MemoryBytes int64  `json:"memoryBytes"`
}

// MetricsGateway is the Go counterpart of KubernetesApplicationMetricsGateway.
type MetricsGateway struct{ pool *Pool }

func NewMetricsGateway(pool *Pool) *MetricsGateway { return &MetricsGateway{pool: pool} }

// GetPodMetrics sums container usage per application pod. Any failure (for
// example metrics-server missing) is logged and yields an empty slice.
func (g *MetricsGateway) GetPodMetrics(ctx context.Context, environment *domain.Environment, namespace, applicationName string) ([]PodMetricSnapshot, error) {
	client, err := g.pool.Get(environment.KubernetesApiServer)
	if err != nil {
		return []PodMetricSnapshot{}, err
	}
	pods, err := listApplicationPods(ctx, client, namespace, applicationName)
	if err != nil {
		slog.Warn("Failed to read pod metrics", "namespace", namespace, "application", applicationName, "error", err.Error())
		return []PodMetricSnapshot{}, nil
	}
	if len(pods) == 0 {
		return []PodMetricSnapshot{}, nil
	}
	podMetrics, err := client.Metrics.MetricsV1beta1().PodMetricses(namespace).List(ctx, metav1.ListOptions{})
	if err != nil {
		slog.Warn("Failed to read pod metrics", "namespace", namespace, "application", applicationName, "error", err.Error())
		return []PodMetricSnapshot{}, nil
	}
	return SummarizePodMetrics(pods, podMetrics.Items), nil
}

// SummarizePodMetrics keeps the metrics of the given pods, sums container
// usage and sorts by pod name.
func SummarizePodMetrics(pods []corev1.Pod, metrics []metricsv1beta1.PodMetrics) []PodMetricSnapshot {
	podNames := map[string]bool{}
	for _, pod := range pods {
		podNames[pod.Name] = true
	}
	snapshots := []PodMetricSnapshot{}
	for _, podMetric := range metrics {
		if !podNames[podMetric.Name] {
			continue
		}
		snapshot := PodMetricSnapshot{PodName: podMetric.Name}
		for _, container := range podMetric.Containers {
			if cpu, ok := container.Usage[corev1.ResourceCPU]; ok {
				snapshot.CPUMillis += cpu.MilliValue()
			}
			if memory, ok := container.Usage[corev1.ResourceMemory]; ok {
				snapshot.MemoryBytes += memory.Value()
			}
		}
		snapshots = append(snapshots, snapshot)
	}
	sort.Slice(snapshots, func(i, j int) bool { return snapshots[i].PodName < snapshots[j].PodName })
	return snapshots
}
