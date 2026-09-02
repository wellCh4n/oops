package podfs

import (
	"bytes"
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"time"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/k8s"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/util/httpstream"
	"k8s.io/client-go/kubernetes/scheme"
	"k8s.io/client-go/rest"
	"k8s.io/client-go/tools/remotecommand"
	"k8s.io/client-go/util/exec"
)

// target identifies the container an exec runs in.
type target struct {
	apiServer *domain.KubernetesApiServer
	namespace string
	pod       string
	container string
}

// execRequest describes one scripted exec.
type execRequest struct {
	script           string
	args             []string
	timeout          time.Duration
	timeoutMessage   string
	interruptMessage string
	// stdin, when non-nil, is fed to the process and the exec asks for stdin.
	stdin []byte
	// stdout, when non-nil, receives output directly instead of being captured.
	stdout io.Writer
}

// execResult is what an exec left behind. failure is a transport-level error
// (the Java ExecListener.onFailure); a non-zero exit is not a failure because
// the scripts exit 1 to signal ordinary conditions via stderr markers.
type execResult struct {
	stdout   *cappedBuffer
	stderr   *cappedBuffer
	exitCode int
	failure  error
}

func (r *execResult) failed() bool { return r.failure != nil }

// cappedBuffer keeps at most maxOutputBytes+1 bytes and counts the rest, so a
// caller can detect overflow without buffering unbounded output.
type cappedBuffer struct {
	buffer bytes.Buffer
	total  int64
}

func (b *cappedBuffer) Write(data []byte) (int, error) {
	b.total += int64(len(data))
	if remaining := maxOutputBytes + 1 - b.buffer.Len(); remaining > 0 {
		if len(data) > remaining {
			data = data[:remaining]
		}
		b.buffer.Write(data)
	}
	return len(data), nil
}

func (b *cappedBuffer) String() string   { return b.buffer.String() }
func (b *cappedBuffer) overflowed() bool { return b.total > maxOutputBytes }

// runGeneric runs a script with the default 15s timeout and generic messages.
func (g *Gateway) runGeneric(ctx context.Context, execTarget target, script string, args ...string) (*execResult, error) {
	return g.run(ctx, execTarget, execRequest{
		script:           script,
		args:             args,
		timeout:          execTimeout,
		timeoutMessage:   "Operation timed out",
		interruptMessage: "Operation interrupted",
	})
}

// run executes `sh -c "<script>\n" sh <args...>` without a TTY. Timeouts and
// caller cancellation are turned into the request's BizError messages; any
// other transport error is recorded on the result as failure.
func (g *Gateway) run(ctx context.Context, execTarget target, request execRequest) (*execResult, error) {
	client, err := g.pool.Get(execTarget.apiServer)
	if err != nil {
		return nil, err
	}
	// The pooled config carries the 10s request timeout meant for plain API
	// calls; an exec must be governed by its own deadline instead.
	config := rest.CopyConfig(client.Config)
	config.Timeout = 0

	command := append([]string{"sh", "-c", request.script + "\n", "sh"}, request.args...)
	options := &corev1.PodExecOptions{
		Container: execTarget.container,
		Command:   command,
		Stdin:     request.stdin != nil,
		Stdout:    true,
		Stderr:    true,
	}
	executor, err := newExecutor(config, execTarget.namespace, execTarget.pod, options)
	if err != nil {
		return nil, k8s.TranslateError(err)
	}

	result := &execResult{stdout: &cappedBuffer{}, stderr: &cappedBuffer{}}
	var stdout io.Writer = result.stdout
	if request.stdout != nil {
		stdout = request.stdout
	}
	var stdin io.Reader
	if request.stdin != nil {
		stdin = bytes.NewReader(request.stdin)
	}

	execCtx, cancel := context.WithTimeout(ctx, request.timeout)
	defer cancel()
	streamErr := executor.StreamWithContext(execCtx, remotecommand.StreamOptions{
		Stdin:  stdin,
		Stdout: stdout,
		Stderr: result.stderr,
	})
	if streamErr == nil {
		return result, nil
	}

	var exitErr exec.CodeExitError
	if errors.As(streamErr, &exitErr) {
		result.exitCode = exitErr.Code
		return result, nil
	}
	switch {
	case errors.Is(ctx.Err(), context.Canceled):
		return nil, domain.Biz(request.interruptMessage)
	case errors.Is(execCtx.Err(), context.DeadlineExceeded):
		return nil, domain.Biz(request.timeoutMessage)
	}
	slog.Warn("Exec failed", "pod", execTarget.namespace+"/"+execTarget.pod, "container", execTarget.container, "error", streamErr)
	result.failure = streamErr
	return result, nil
}

// newExecutor builds a remotecommand executor for the exec sub-resource,
// preferring WebSocket and falling back to SPDY when the upgrade is refused.
func newExecutor(config *rest.Config, namespace, pod string, options *corev1.PodExecOptions) (remotecommand.Executor, error) {
	restConfig := rest.CopyConfig(config)
	restConfig.APIPath = "/api"
	restConfig.GroupVersion = &corev1.SchemeGroupVersion
	restConfig.NegotiatedSerializer = scheme.Codecs.WithoutConversion()
	restClient, err := rest.RESTClientFor(restConfig)
	if err != nil {
		return nil, err
	}
	execURL := restClient.Post().
		Resource("pods").
		Namespace(namespace).
		Name(pod).
		SubResource("exec").
		VersionedParams(options, scheme.ParameterCodec).
		URL()
	return executorFor(config, execURL)
}

func executorFor(config *rest.Config, execURL *url.URL) (remotecommand.Executor, error) {
	webSocketExecutor, err := remotecommand.NewWebSocketExecutor(config, http.MethodGet, execURL.String())
	if err != nil {
		return nil, err
	}
	spdyExecutor, err := remotecommand.NewSPDYExecutor(config, http.MethodPost, execURL)
	if err != nil {
		return nil, err
	}
	return remotecommand.NewFallbackExecutor(webSocketExecutor, spdyExecutor, func(err error) bool {
		return httpstream.IsUpgradeFailure(err) || httpstream.IsHTTPSProxyError(err)
	})
}
