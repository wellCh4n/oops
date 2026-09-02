package sandbox

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/k8s"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/client-go/kubernetes/scheme"
	"k8s.io/client-go/tools/remotecommand"
	utilexec "k8s.io/client-go/util/exec"
)

// execCloseCode is what the Java gateway reported as "exitCode" for every
// instance exec: Fabric8 handed ExecListener.onClose the WebSocket close code
// (1000 = normal closure) rather than the process exit status, so `true` and
// `exit 3` both came back as 1000. The integration suite
// (tests/integration/test_sandbox.py,
// test_exec_reports_the_websocket_close_code_rather_than_the_exit_status) pins
// that value, so parity wins here. The real status is still recovered by
// processExitCode and is one line away should the defect be fixed on purpose.
const execCloseCode = 1000

// ExecInstance runs `/bin/sh -c <command>` in the instance's container with no
// TTY and no stdin, returning stdout followed by stderr.
func (g *Gateway) ExecInstance(ctx context.Context, apiServer *domain.KubernetesApiServer, workNamespace, sandboxID, command string, timeoutSeconds int) (ExecResult, error) {
	var stdout, stderr bytes.Buffer
	if err := g.execInPod(ctx, apiServer, workNamespace, sandboxID, command, timeoutSeconds, &stdout, &stderr); err != nil {
		return ExecResult{}, err
	}
	return ExecResult{ExitCode: execCloseCode, Output: stdout.String() + stderr.String()}, nil
}

// StreamExecInstance runs the command and hands every stdout/stderr line to
// onLine as it arrives (a trailing partial line is flushed at the end), then
// the exit code to onExit. The returned error is the failure to complete with.
func (g *Gateway) StreamExecInstance(ctx context.Context, apiServer *domain.KubernetesApiServer, workNamespace, sandboxID, command string, timeoutSeconds int, onLine func(string), onExit func(int)) error {
	var emit sync.Mutex
	guardedOnLine := func(line string) {
		if onLine == nil {
			return
		}
		emit.Lock()
		defer emit.Unlock()
		onLine(line)
	}
	stdout := newLineWriter(guardedOnLine)
	stderr := newLineWriter(guardedOnLine)

	err := g.execInPod(ctx, apiServer, workNamespace, sandboxID, command, timeoutSeconds, stdout, stderr)
	stdout.Flush()
	stderr.Flush()
	if err != nil {
		if domain.BizMessage(err) != execTimedOutMessage {
			slog.Warn("Sandbox stream exec ended abnormally", "sandboxId", sandboxID, "error", err)
		}
		return err
	}
	if onExit != nil {
		onExit(execCloseCode)
	}
	return nil
}

const (
	execTimedOutMessage = "Sandbox exec timed out"
	execFailedPrefix    = "Sandbox exec failed: "
)

// execInPod performs the remote exec. A dedicated streaming client is used
// because the pooled client's 10s HTTP timeout would cut long commands short.
func (g *Gateway) execInPod(ctx context.Context, apiServer *domain.KubernetesApiServer, workNamespace, sandboxID, command string, timeoutSeconds int, stdout, stderr interface{ Write([]byte) (int, error) }) error {
	client, err := k8s.StreamingClient(apiServer)
	if err != nil {
		return domain.BizWrap(execFailedPrefix+err.Error(), err)
	}
	podName := PodName(sandboxID)

	request := client.Clientset.CoreV1().RESTClient().Post().
		Resource("pods").
		Namespace(workNamespace).
		Name(podName).
		SubResource("exec").
		VersionedParams(&corev1.PodExecOptions{
			Container: ContainerName,
			Command:   []string{binSh, "-c", command},
			Stdin:     false,
			Stdout:    true,
			Stderr:    true,
			TTY:       false,
		}, scheme.ParameterCodec)

	executor, err := remotecommand.NewSPDYExecutor(client.Config, http.MethodPost, request.URL())
	if err != nil {
		slog.Warn("Sandbox exec failed", "namespace", workNamespace, "pod", podName, "error", err)
		return domain.BizWrap(execFailedPrefix+err.Error(), err)
	}

	execCtx, cancel := context.WithTimeout(ctx, time.Duration(timeoutSeconds)*time.Second)
	defer cancel()

	err = executor.StreamWithContext(execCtx, remotecommand.StreamOptions{Stdout: stdout, Stderr: stderr, Tty: false})
	if err == nil {
		return nil
	}
	if _, exited := processExitCode(err); exited {
		// A non-zero process exit is a completed exec, not a failure.
		return nil
	}
	if errors.Is(execCtx.Err(), context.DeadlineExceeded) {
		return domain.Biz(execTimedOutMessage)
	}
	slog.Warn("Sandbox exec failed", "namespace", workNamespace, "pod", podName, "error", err)
	return domain.BizWrap(execFailedPrefix+err.Error(), err)
}

// processExitCode extracts the real process exit status from a remotecommand
// error, when the command itself exited non-zero.
func processExitCode(err error) (int, bool) {
	var codeErr utilexec.CodeExitError
	if errors.As(err, &codeErr) {
		return codeErr.Code, true
	}
	return 0, false
}

// lineWriter mirrors SseLineOutputStream: buffer bytes, emit on each '\n'
// (newline excluded), and flush whatever remains at the end.
type lineWriter struct {
	mu      sync.Mutex
	pending bytes.Buffer
	onLine  func(string)
}

func newLineWriter(onLine func(string)) *lineWriter {
	return &lineWriter{onLine: onLine}
}

func (w *lineWriter) Write(data []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.pending.Write(data)
	for {
		buffered := w.pending.Bytes()
		newline := bytes.IndexByte(buffered, '\n')
		if newline < 0 {
			break
		}
		line := string(buffered[:newline])
		w.pending.Next(newline + 1)
		w.onLine(line)
	}
	return len(data), nil
}

// Flush emits a trailing partial line, if any.
func (w *lineWriter) Flush() {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.pending.Len() == 0 {
		return
	}
	line := w.pending.String()
	w.pending.Reset()
	w.onLine(line)
}
