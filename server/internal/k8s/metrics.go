package k8s

import (
	"context"
	"sort"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"
	metricsclient "k8s.io/metrics/pkg/client/clientset/versioned"
)

// PodMetricSnapshot mirrors the Java record: {podName, cpuMillis, memoryBytes}.
type PodMetricSnapshot struct {
	PodName     string `json:"podName"`
	CPUMillis   int64  `json:"cpuMillis"`
	MemoryBytes int64  `json:"memoryBytes"`
}

// ListPodMetrics reads the metrics.k8s.io API like
// KubernetesApplicationMetricsGateway; an absent metrics-server degrades to an
// empty reading rather than an error.
func ListPodMetrics(ctx context.Context, client *kubernetes.Clientset, restConfig *rest.Config, namespace, applicationName string) ([]PodMetricSnapshot, error) {
	pods, err := client.CoreV1().Pods(namespace).List(ctx, metav1.ListOptions{
		LabelSelector: applicationSelector(applicationName),
	})
	if err != nil {
		return nil, err
	}
	applicationPods := map[string]struct{}{}
	for _, pod := range pods.Items {
		applicationPods[pod.Name] = struct{}{}
	}
	if len(applicationPods) == 0 {
		return []PodMetricSnapshot{}, nil
	}

	metrics, err := metricsclient.NewForConfig(restConfig)
	if err != nil {
		return []PodMetricSnapshot{}, nil
	}
	podMetricsList, err := metrics.MetricsV1beta1().PodMetricses(namespace).List(ctx, metav1.ListOptions{})
	if err != nil {
		return []PodMetricSnapshot{}, nil // metrics-server may be absent
	}
	snapshots := []PodMetricSnapshot{}
	for _, podMetrics := range podMetricsList.Items {
		if _, matches := applicationPods[podMetrics.Name]; !matches {
			continue
		}
		snapshot := PodMetricSnapshot{PodName: podMetrics.Name}
		for _, container := range podMetrics.Containers {
			snapshot.CPUMillis += container.Usage.Cpu().MilliValue()
			snapshot.MemoryBytes += container.Usage.Memory().Value()
		}
		snapshots = append(snapshots, snapshot)
	}
	sort.Slice(snapshots, func(i, j int) bool { return snapshots[i].PodName < snapshots[j].PodName })
	return snapshots, nil
}
