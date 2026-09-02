package stream

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/k8s"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/client-go/tools/remotecommand"
	"k8s.io/client-go/util/exec"
)

// terminalShell mirrors KubernetesTerminalSessionGateway: prefer bash, fall
// back to /bin/sh, and advertise a 256-colour terminal either way.
const terminalShell = "export TERM=xterm-256color; if command -v bash >/dev/null 2>&1; then exec bash; else exec /bin/sh; fi"

// TerminalSession is the inbound half of an interactive exec session.
type TerminalSession interface {
	// Write forwards raw bytes to the remote shell's stdin.
	Write(data []byte) error
	// Resize queues a terminal size change. The current UI never sends one,
	// so the remote TTY keeps its default size until a client does.
	Resize(cols, rows uint16)
	// Close ends the session and releases the Kubernetes connection.
	Close()
}

// OpenTerminal execs an interactive shell in the container with a TTY.
// stdout and stderr both reach the sink as binary frames; when the shell
// exits the sink is closed normally, and a transport failure closes it with
// an error after a warning log. An error opening the exec is returned and
// nothing is sent to the sink.
func OpenTerminal(ctx context.Context, apiServer *domain.KubernetesApiServer, namespace, pod, container string, sink Sink) (TerminalSession, error) {
	client, err := k8s.StreamingClient(apiServer)
	if err != nil {
		return nil, err
	}
	executor, err := NewExecutor(client.Config, namespace, pod, &corev1.PodExecOptions{
		Container: container,
		Command:   []string{"sh", "-c", terminalShell},
		Stdin:     true,
		Stdout:    true,
		Stderr:    true,
		TTY:       true,
	})
	if err != nil {
		return nil, err
	}

	sessionHandle := newHandle(ctx)
	stdinReader, stdinWriter := io.Pipe()
	sessionHandle.add(stdinWriter)
	session := &terminalSession{
		handle:      sessionHandle,
		stdin:       stdinWriter,
		resizeQueue: newResizeQueue(),
	}
	sessionHandle.add(session.resizeQueue)

	go func() {
		streamErr := executor.StreamWithContext(sessionHandle.ctx, remotecommand.StreamOptions{
			Stdin:             stdinReader,
			Stdout:            sinkWriter{sink: sink},
			Stderr:            sinkWriter{sink: sink},
			Tty:               true,
			TerminalSizeQueue: session.resizeQueue,
		})
		_ = stdinReader.Close()
		switch {
		case streamErr == nil, isExitCode(streamErr), isDone(sessionHandle.ctx):
			// The shell ended (any exit status) or we closed the session ourselves.
			_ = sink.Close()
		default:
			slog.Warn("Terminal session failed", "pod", namespace+"/"+pod, "error", streamErr,
				"message", "Terminal session failed for pod "+namespace+"/"+pod+": "+streamErr.Error())
			_ = sink.CloseWithError()
		}
		_ = sessionHandle.Close()
	}()

	return session, nil
}

func isExitCode(err error) bool {
	var exitErr exec.CodeExitError
	return errors.As(err, &exitErr)
}

type terminalSession struct {
	handle      *handle
	stdin       *io.PipeWriter
	resizeQueue *resizeQueue
}

func (s *terminalSession) Write(data []byte) error {
	_, err := s.stdin.Write(data)
	return err
}

func (s *terminalSession) Resize(cols, rows uint16) {
	s.resizeQueue.push(remotecommand.TerminalSize{Width: cols, Height: rows})
}

func (s *terminalSession) Close() {
	_ = s.handle.Close()
}

// sinkWriter adapts a Sink to io.Writer, dropping output once the sink closed
// (like StreamSinkOutputStream) so a client that went away never errors the exec.
type sinkWriter struct{ sink Sink }

func (w sinkWriter) Write(data []byte) (int, error) {
	if w.sink.IsOpen() {
		copied := make([]byte, len(data))
		copy(copied, data)
		if err := w.sink.SendBinary(copied); err != nil {
			return len(data), nil
		}
	}
	return len(data), nil
}

// resizeQueue implements remotecommand.TerminalSizeQueue over a channel; Next
// blocks until a size arrives and returns nil once the queue is closed.
type resizeQueue struct {
	sizes chan remotecommand.TerminalSize
	once  sync.Once
	done  chan struct{}
}

func newResizeQueue() *resizeQueue {
	return &resizeQueue{sizes: make(chan remotecommand.TerminalSize, 8), done: make(chan struct{})}
}

func (q *resizeQueue) push(size remotecommand.TerminalSize) {
	select {
	case <-q.done:
	case q.sizes <- size:
	default:
		// Drop the oldest queued size so the newest always wins.
		select {
		case <-q.sizes:
		default:
		}
		select {
		case q.sizes <- size:
		default:
		}
	}
}

func (q *resizeQueue) Next() *remotecommand.TerminalSize {
	select {
	case <-q.done:
		return nil
	case size := <-q.sizes:
		return &size
	}
}

func (q *resizeQueue) Close() error {
	q.once.Do(func() { close(q.done) })
	return nil
}
