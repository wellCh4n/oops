package httpapi

import (
	"bufio"
	"context"
	"encoding/json"
	"time"

	"github.com/gin-gonic/gin"
	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"github.com/wellch4n/oops/server/internal/k8s"
	"github.com/wellch4n/oops/server/internal/store"
)

func sendJSON(sink *wsSink, payload map[string]any) error {
	encoded, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	return sink.sendText(string(encoded))
}

// pipelineLogWebSocket mirrors PipelineLogWebSocketHandler + the log stream
// gateway: a "steps" frame with the container names, then "[container] line"
// step frames per container in order, then "done".
func (s *Server) pipelineLogWebSocket(c *gin.Context) {
	namespace, name, pipelineID := c.Param("namespace"), c.Param("name"), c.Param("id")
	pipeline, err := s.store.FindPipeline(c.Request.Context(), namespace, name, pipelineID)
	if err != nil {
		return
	}
	environmentName := ""
	if pipeline.Environment != nil {
		environmentName = *pipeline.Environment
	}
	cluster, connected := s.cluster(c, environmentName)
	if !connected {
		return
	}
	environment, err := s.store.FindEnvironmentFullByName(c.Request.Context(), environmentName)
	if err != nil {
		return
	}
	workNamespace := ""
	if environment.WorkNamespace != nil {
		workNamespace = *environment.WorkNamespace
	}

	connection, err := upgrader.Upgrade(c.Writer, c.Request, nil)
	if err != nil {
		return
	}
	defer connection.Close()
	sink := &wsSink{connection: connection}

	ctx, cancel := context.WithCancel(c.Request.Context())
	defer cancel()
	go sink.heartbeat(ctx)
	go func() { // ping/pong reader
		for {
			messageType, payload, err := connection.ReadMessage()
			if err != nil {
				cancel()
				return
			}
			if messageType == 1 && string(payload) == "ping" {
				if sink.sendText("pong") != nil {
					cancel()
					return
				}
			}
		}
	}()

	job, err := cluster.Clientset.BatchV1().Jobs(workNamespace).Get(ctx, pipeline.Name, metav1.GetOptions{})
	if apierrors.IsNotFound(err) {
		message := "Job not found"
		if pipeline.Status != nil && isFinishedStatus(*pipeline.Status) {
			message = "Logs expired: the build job has been cleaned up"
		}
		_ = sendJSON(sink, map[string]any{"type": "error", "data": message})
		return
	}
	if err != nil {
		_ = sendJSON(sink, map[string]any{"type": "error", "data": "Failed to watch pipeline logs: " + err.Error()})
		return
	}

	containers := []string{}
	for _, container := range job.Spec.Template.Spec.InitContainers {
		containers = append(containers, container.Name)
	}
	for _, container := range job.Spec.Template.Spec.Containers {
		containers = append(containers, container.Name)
	}
	if err := sendJSON(sink, map[string]any{"type": "steps", "data": containers}); err != nil {
		return
	}

	for _, containerName := range containers {
		if ctx.Err() != nil {
			return
		}
		s.streamJobContainerLogs(ctx, cluster, workNamespace, pipeline.Name, containerName, sink)
	}
	_ = sendJSON(sink, map[string]any{"type": "done"})
}

func isFinishedStatus(status string) bool {
	return status == store.StatusSucceeded || status == store.StatusError || status == store.StatusStopped
}

func (s *Server) streamJobContainerLogs(ctx context.Context, cluster *k8s.Cluster, workNamespace, jobName, containerName string, sink *wsSink) {
	// Wait for the job pod, then for this container to start or terminate.
	var podName string
	deadline := time.Now().Add(5 * time.Minute)
	for time.Now().Before(deadline) && ctx.Err() == nil {
		pods, err := cluster.Clientset.CoreV1().Pods(workNamespace).List(ctx, metav1.ListOptions{
			LabelSelector: "job-name=" + jobName,
		})
		if err == nil && len(pods.Items) > 0 {
			pod := &pods.Items[0]
			podName = pod.Name
			if containerStartedOrDone(pod, containerName) {
				break
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Second):
		}
	}
	if podName == "" || ctx.Err() != nil {
		return
	}

	follow := true
	logStream, err := cluster.Clientset.CoreV1().Pods(workNamespace).
		GetLogs(podName, &corev1.PodLogOptions{Container: containerName, Follow: follow}).Stream(ctx)
	if err != nil {
		return
	}
	defer logStream.Close()
	scanner := bufio.NewScanner(logStream)
	scanner.Buffer(make([]byte, 64*1024), 1024*1024)
	for scanner.Scan() {
		if sendJSON(sink, map[string]any{
			"type": "step", "data": "[" + containerName + "] " + scanner.Text(), "container": containerName,
		}) != nil {
			return
		}
	}
}

func containerStartedOrDone(pod *corev1.Pod, containerName string) bool {
	statuses := append([]corev1.ContainerStatus{}, pod.Status.InitContainerStatuses...)
	statuses = append(statuses, pod.Status.ContainerStatuses...)
	for _, containerStatus := range statuses {
		if containerStatus.Name == containerName &&
			(containerStatus.State.Running != nil || containerStatus.State.Terminated != nil) {
			return true
		}
	}
	return false
}
