package stream

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"time"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/k8s"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
)

const (
	logsExpiredMessage      = "Logs expired: the build job has been cleaned up"
	jobNotFoundMessage      = "Job not found"
	podWaitTimeout          = 5 * time.Minute
	podWaitPollInterval     = time.Second
	podWaitRetryDelay       = time.Second
	maxLogRetries           = 10
	logRetryBackoffStep     = 2 * time.Second
	logRetryBackoffCeiling  = 30 * time.Second
	jobNameLabel            = "job-name"
	pipelineLogMessageType  = "type"
	pipelineLogStepType     = "step"
	pipelineLogStepsType    = "steps"
	pipelineLogDoneType     = "done"
	pipelineLogErrorType    = "error"
	watchFailedMessagePrefx = "Failed to watch pipeline logs: "
)

// stepMessage is emitted per log line; field order is part of the protocol.
type stepMessage struct {
	Type      string `json:"type"`
	Data      string `json:"data"`
	Container string `json:"container"`
	Time      string `json:"time,omitempty"`
}

type stepsMessage struct {
	Type string   `json:"type"`
	Data []string `json:"data"`
}

type errorMessage struct {
	Type string `json:"type"`
	Data string `json:"data"`
}

type doneMessage struct {
	Type string `json:"type"`
}

// StreamPipelineLog replays and follows the build Job's container logs in
// spec order, one JSON text frame per line, then sends {"type":"done"} and
// closes the sink. If the Job is gone only an error frame is sent and the
// sink is left open, matching the Java gateway. The returned closer stops
// the stream and releases the Kubernetes connection.
func StreamPipelineLog(ctx context.Context, apiServer *domain.KubernetesApiServer, workNamespace, jobName string, pipelineFinished bool, sink Sink) (io.Closer, error) {
	client, err := k8s.StreamingClient(apiServer)
	if err != nil {
		return nil, err
	}
	streamHandle := newHandle(ctx)
	streamer := &pipelineLogStreamer{
		clientset:     client.Clientset,
		workNamespace: workNamespace,
		jobName:       jobName,
		finished:      pipelineFinished,
		sink:          sink,
		handle:        streamHandle,
	}
	go streamer.run()
	return streamHandle, nil
}

type pipelineLogStreamer struct {
	clientset     kubernetes.Interface
	workNamespace string
	jobName       string
	finished      bool
	sink          Sink
	handle        *handle
}

func (s *pipelineLogStreamer) open() bool { return s.handle.isOpen(s.sink) }

func (s *pipelineLogStreamer) send(message any) {
	encoded, err := json.Marshal(message)
	if err != nil {
		return
	}
	_ = s.sink.SendText(string(encoded))
}

func (s *pipelineLogStreamer) sendError(message string) {
	s.send(errorMessage{Type: pipelineLogErrorType, Data: message})
}

func (s *pipelineLogStreamer) run() {
	defer s.handle.Close()
	defer func() {
		if recovered := recover(); recovered != nil {
			slog.Error("Pipeline log stream panicked", "job", s.jobName, "panic", recovered)
			if s.open() {
				s.sendError(fmt.Sprintf("%s%v", watchFailedMessagePrefx, recovered))
			}
		}
	}()

	if err := s.stream(); err != nil && s.open() {
		s.sendError(watchFailedMessagePrefx + err.Error())
	}
}

func (s *pipelineLogStreamer) stream() error {
	ctx := s.handle.ctx
	job, err := s.clientset.BatchV1().Jobs(s.workNamespace).Get(ctx, s.jobName, metav1.GetOptions{})
	if err != nil {
		if k8s.IsNotFound(err) {
			if s.open() {
				if s.finished {
					s.sendError(logsExpiredMessage)
				} else {
					s.sendError(jobNotFoundMessage)
				}
			}
			return nil
		}
		return k8s.TranslateError(err)
	}

	containers := make([]string, 0)
	for _, container := range job.Spec.Template.Spec.InitContainers {
		containers = append(containers, container.Name)
	}
	for _, container := range job.Spec.Template.Spec.Containers {
		containers = append(containers, container.Name)
	}
	s.send(stepsMessage{Type: pipelineLogStepsType, Data: containers})

	for _, containerName := range containers {
		if !s.open() {
			break
		}
		if err := s.streamContainer(containerName); err != nil {
			return err
		}
	}

	if s.open() {
		s.send(doneMessage{Type: pipelineLogDoneType})
		_ = s.sink.Close()
	}
	return nil
}

func (s *pipelineLogStreamer) streamContainer(containerName string) error {
	ctx := s.handle.ctx
	podName := s.waitForPod()
	if podName == "" {
		return nil
	}
	if !s.waitForContainerStarted(podName, containerName) {
		return nil
	}

	linesSent := 0
	retries := 0
	// Survives a reconnect so a resumed stream does not start out unstamped.
	lastTime := ""

	for s.open() && retries <= maxLogRetries {
		followErr := s.followContainer(ctx, podName, containerName, &linesSent, &lastTime)
		if followErr == nil {
			break
		}
		if !s.open() {
			break
		}
		retries++
		refreshed, err := s.clientset.CoreV1().Pods(s.workNamespace).Get(ctx, podName, metav1.GetOptions{})
		if err == nil && isContainerTerminated(refreshed, containerName) {
			break
		}
		if !sleep(ctx, min(logRetryBackoffStep*time.Duration(retries), logRetryBackoffCeiling)) {
			break
		}
	}
	return nil
}

// followContainer opens one timestamped follow and forwards lines beyond
// *linesSent. A nil return means the stream ended cleanly (container finished).
func (s *pipelineLogStreamer) followContainer(ctx context.Context, podName, containerName string, linesSent *int, lastTime *string) error {
	logStream, err := s.clientset.CoreV1().Pods(s.workNamespace).GetLogs(podName, &corev1.PodLogOptions{
		Container:  containerName,
		Follow:     true,
		Timestamps: true,
	}).Stream(ctx)
	if err != nil {
		return err
	}
	s.handle.add(logStream)
	defer func() {
		s.handle.remove(logStream)
		_ = logStream.Close()
	}()

	scanner := NewLineScanner(logStream)
	lineCount := 0
	for s.open() && scanner.Scan() {
		if lineCount >= *linesSent {
			stamp, text := ParseTimestampedLine(scanner.Text())
			// Git and buildah redraw progress with a bare carriage return, which the
			// scanner ends a line on exactly like a newline — but only the first
			// fragment of that physical line carries the API server's stamp, so the
			// last stamp is carried forward to keep the time column filled.
			if stamp != "" {
				*lastTime = stamp
			}
			s.send(stepMessage{
				Type:      pipelineLogStepType,
				Data:      "[" + containerName + "] " + text,
				Container: containerName,
				Time:      *lastTime,
			})
			*linesSent++
		}
		lineCount++
	}
	return scanner.Err()
}

// waitForPod returns the name of the first pod labelled with the job name,
// retrying every second while the stream is open; "" when the stream closed.
func (s *pipelineLogStreamer) waitForPod() string {
	ctx := s.handle.ctx
	for s.open() {
		pods, err := s.clientset.CoreV1().Pods(s.workNamespace).List(ctx, metav1.ListOptions{
			LabelSelector: jobNameLabel + "=" + s.jobName,
			Limit:         1,
		})
		if err == nil && len(pods.Items) > 0 {
			return pods.Items[0].Name
		}
		if !s.open() || !sleep(ctx, podWaitRetryDelay) {
			return ""
		}
	}
	return ""
}

// waitForContainerStarted blocks until the container is running or
// terminated, polling while the stream is open.
func (s *pipelineLogStreamer) waitForContainerStarted(podName, containerName string) bool {
	ctx := s.handle.ctx
	for s.open() {
		pod, err := s.clientset.CoreV1().Pods(s.workNamespace).Get(ctx, podName, metav1.GetOptions{})
		if err == nil && isContainerStarted(pod, containerName) {
			return true
		}
		if !s.open() || !sleep(ctx, podWaitPollInterval) {
			return false
		}
	}
	return false
}

func containerStatuses(pod *corev1.Pod) []corev1.ContainerStatus {
	if pod == nil {
		return nil
	}
	statuses := make([]corev1.ContainerStatus, 0, len(pod.Status.InitContainerStatuses)+len(pod.Status.ContainerStatuses))
	statuses = append(statuses, pod.Status.InitContainerStatuses...)
	statuses = append(statuses, pod.Status.ContainerStatuses...)
	return statuses
}

func isContainerStarted(pod *corev1.Pod, containerName string) bool {
	for _, status := range containerStatuses(pod) {
		if status.Name == containerName && (status.State.Running != nil || status.State.Terminated != nil) {
			return true
		}
	}
	return false
}

func isContainerTerminated(pod *corev1.Pod, containerName string) bool {
	for _, status := range containerStatuses(pod) {
		if status.Name == containerName && status.State.Terminated != nil {
			return true
		}
	}
	return false
}

// ParseTimestampedLine splits one line as Kubernetes hands it over when logs
// are requested with timestamps: an RFC3339 instant, a space, then whatever
// the container wrote. A line without a readable stamp comes back whole with
// an empty time; a bare stamp yields the stamp and empty text.
func ParseTimestampedLine(line string) (stamp string, text string) {
	separator := indexOfSpace(line)
	if separator <= 0 {
		if isInstant(line) {
			return line, ""
		}
		return "", line
	}
	candidate := line[:separator]
	if !isInstant(candidate) {
		return "", line
	}
	return candidate, line[separator+1:]
}

func indexOfSpace(line string) int {
	for index := 0; index < len(line); index++ {
		if line[index] == ' ' {
			return index
		}
	}
	return -1
}

// isInstant accepts what java.time.Instant.parse accepts: an ISO-8601 instant
// with optional fractional seconds and a Z or numeric offset.
func isInstant(value string) bool {
	if value == "" {
		return false
	}
	_, err := time.Parse(time.RFC3339Nano, value)
	return err == nil
}
