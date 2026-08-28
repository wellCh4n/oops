package httpapi

import (
	"bufio"
	"context"
	"io"
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/client-go/kubernetes/scheme"
	"k8s.io/client-go/tools/remotecommand"

	"github.com/wellch4n/oops/server/internal/k8s"
)

var upgrader = websocket.Upgrader{
	// Same posture as the Java config's setAllowedOrigins("*").
	CheckOrigin: func(*http.Request) bool { return true },
}

// wsSink serializes writes to one WebSocket connection, like WebSocketStreamSink.
type wsSink struct {
	mutex      sync.Mutex
	connection *websocket.Conn
}

func (sink *wsSink) sendText(text string) error {
	sink.mutex.Lock()
	defer sink.mutex.Unlock()
	return sink.connection.WriteMessage(websocket.TextMessage, []byte(text))
}

func (sink *wsSink) sendBinary(data []byte) error {
	sink.mutex.Lock()
	defer sink.mutex.Unlock()
	return sink.connection.WriteMessage(websocket.BinaryMessage, data)
}

func (sink *wsSink) ping() error {
	sink.mutex.Lock()
	defer sink.mutex.Unlock()
	return sink.connection.WriteControl(websocket.PingMessage, nil, time.Now().Add(5*time.Second))
}

// heartbeat mirrors WebSocketSessionSupport.startHeartbeat's native ping frames.
func (sink *wsSink) heartbeat(ctx context.Context) {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if sink.ping() != nil {
				return
			}
		}
	}
}

// podLogWebSocket mirrors PodLogWebSocketHandler: tail the last 2000 lines,
// stream line-by-line as text frames, answer text "ping" with "pong".
func (s *Server) podLogWebSocket(c *gin.Context) {
	cluster, connected := s.cluster(c, c.Query("environment"))
	if !connected {
		return
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

	namespace, pod := c.Param("namespace"), c.Param("pod")
	tail := int64(2000)
	logStream, err := cluster.Clientset.CoreV1().Pods(namespace).
		GetLogs(pod, &corev1.PodLogOptions{Follow: true, TailLines: &tail}).Stream(ctx)
	if err != nil {
		_ = sink.sendText("Pod not found: " + pod)
		return
	}
	defer logStream.Close()

	go func() {
		// Reader loop: answer pings; any read error ends the session.
		for {
			messageType, payload, err := connection.ReadMessage()
			if err != nil {
				cancel()
				return
			}
			if messageType == websocket.TextMessage && string(payload) == "ping" {
				if sink.sendText("pong") != nil {
					cancel()
					return
				}
			}
		}
	}()

	scanner := bufio.NewScanner(logStream)
	scanner.Buffer(make([]byte, 64*1024), 1024*1024)
	scanner.Split(k8s.ScanLogLines)
	for scanner.Scan() {
		if sink.sendText(scanner.Text()) != nil {
			return
		}
	}
	if err := scanner.Err(); err != nil && ctx.Err() == nil {
		_ = sink.sendText("Error reading logs: " + err.Error())
	}
}

// terminalShellCommand matches the Java exec: prefer bash, fall back to sh.
var terminalShellCommand = []string{"sh", "-c",
	"export TERM=xterm-256color; if command -v bash >/dev/null 2>&1; then exec bash; else exec /bin/sh; fi"}

type websocketStdin struct {
	connection *websocket.Conn
	cancel     context.CancelFunc
}

func (stdin *websocketStdin) Read(buffer []byte) (int, error) {
	_, payload, err := stdin.connection.ReadMessage()
	if err != nil {
		stdin.cancel()
		return 0, io.EOF
	}
	return copy(buffer, payload), nil
}

type binaryFrameWriter struct{ sink *wsSink }

func (writer binaryFrameWriter) Write(data []byte) (int, error) {
	buffered := make([]byte, len(data))
	copy(buffered, data)
	if err := writer.sink.sendBinary(buffered); err != nil {
		return 0, err
	}
	return len(data), nil
}

// terminalWebSocket mirrors TerminalWebSocketHandler: a TTY exec into the
// container named after the application; every WS frame (text or binary) is
// stdin, all output comes back as binary frames.
func (s *Server) terminalWebSocket(c *gin.Context) {
	cluster, connected := s.cluster(c, c.Query("environment"))
	if !connected {
		return
	}
	connection, err := upgrader.Upgrade(c.Writer, c.Request, nil)
	if err != nil {
		return
	}
	defer connection.Close()
	sink := &wsSink{connection: connection}

	ctx, cancel := context.WithCancel(c.Request.Context())
	defer cancel()

	namespace, container, pod := c.Param("namespace"), c.Param("name"), c.Param("pod")
	s.runTerminalExec(ctx, cancel, cluster, namespace, pod, container, connection, sink)
}

func (s *Server) runTerminalExec(ctx context.Context, cancel context.CancelFunc, cluster *k8s.Cluster,
	namespace, pod, container string, connection *websocket.Conn, sink *wsSink) {

	request := cluster.Clientset.CoreV1().RESTClient().Post().
		Resource("pods").Namespace(namespace).Name(pod).SubResource("exec").
		VersionedParams(&corev1.PodExecOptions{
			Container: container,
			Command:   terminalShellCommand,
			Stdin:     true,
			Stdout:    true,
			Stderr:    true,
			TTY:       true,
		}, scheme.ParameterCodec)

	executor, err := remotecommand.NewSPDYExecutor(cluster.Config, http.MethodPost, request.URL())
	if err != nil {
		_ = sink.sendText("Failed to open terminal: " + err.Error())
		return
	}
	output := binaryFrameWriter{sink: sink}
	_ = executor.StreamWithContext(ctx, remotecommand.StreamOptions{
		Stdin:  &websocketStdin{connection: connection, cancel: cancel},
		Stdout: output,
		Stderr: output,
		Tty:    true,
	})
}
