package stream

import (
	"context"
	"io"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/k8s"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// podLogTailLines is how much history a fresh pod log connection replays.
const podLogTailLines int64 = 2000

// StreamPodLog follows the pod's default container log and forwards every
// line as a text frame. A missing pod sends "Pod not found: <pod>" and closes
// the sink; a read error while the sink is open sends "Error reading logs:
// <msg>"; end of stream closes the sink normally. The returned closer stops
// the follow and releases the Kubernetes connection.
func StreamPodLog(ctx context.Context, apiServer *domain.KubernetesApiServer, namespace, pod string, sink Sink) (io.Closer, error) {
	client, err := k8s.StreamingClient(apiServer)
	if err != nil {
		return nil, err
	}
	streamHandle := newHandle(ctx)

	if _, err := client.Clientset.CoreV1().Pods(namespace).Get(streamHandle.ctx, pod, metav1.GetOptions{}); err != nil {
		_ = streamHandle.Close()
		if k8s.IsNotFound(err) {
			_ = sink.SendText("Pod not found: " + pod)
			_ = sink.Close()
			return streamHandle, nil
		}
		return nil, k8s.TranslateError(err)
	}

	tail := podLogTailLines
	logStream, err := client.Clientset.CoreV1().Pods(namespace).GetLogs(pod, &corev1.PodLogOptions{
		Follow:    true,
		TailLines: &tail,
	}).Stream(streamHandle.ctx)
	if err != nil {
		_ = streamHandle.Close()
		return nil, k8s.TranslateError(err)
	}
	streamHandle.add(logStream)

	go func() {
		defer func() {
			if sink.IsOpen() {
				_ = sink.Close()
			}
			_ = streamHandle.Close()
		}()
		scanner := NewLineScanner(logStream)
		for streamHandle.isOpen(sink) && scanner.Scan() {
			_ = sink.SendText(scanner.Text())
		}
		if err := scanner.Err(); err != nil && streamHandle.isOpen(sink) {
			_ = sink.SendText("Error reading logs: " + err.Error())
		}
	}()
	return streamHandle, nil
}
