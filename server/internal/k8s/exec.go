package k8s

import (
	"context"
	"errors"
	"io"
	"net/http"
	"regexp"
	"strconv"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/client-go/kubernetes/scheme"
	"k8s.io/client-go/tools/remotecommand"
	utilexec "k8s.io/utils/exec"
)

// execRaw runs a command in a container, sending combined stdout+stderr to the
// writer. It returns the raw stream error (which carries the exit code).
func execRaw(ctx context.Context, cluster *Cluster, namespace, pod, container string, command []string, combined io.Writer) (string, string, error) {
	request := cluster.Clientset.CoreV1().RESTClient().Post().
		Resource("pods").Namespace(namespace).Name(pod).SubResource("exec").
		VersionedParams(&corev1.PodExecOptions{
			Container: container,
			Command:   command,
			Stdout:    true,
			Stderr:    true,
		}, scheme.ParameterCodec)
	executor, err := remotecommand.NewSPDYExecutor(cluster.Config, http.MethodPost, request.URL())
	if err != nil {
		return "", "", err
	}
	err = executor.StreamWithContext(ctx, remotecommand.StreamOptions{
		Stdout: combined, Stderr: combined,
	})
	return "", "", err
}

var exitCodePattern = regexp.MustCompile(`exit code (\d+)`)

// exitCodeFromError extracts the remote exit status from an exec failure.
func exitCodeFromError(err error) (int, bool) {
	var exitError utilexec.ExitError
	if errors.As(err, &exitError) {
		return exitError.ExitStatus(), true
	}
	if matches := exitCodePattern.FindStringSubmatch(err.Error()); matches != nil {
		if code, parseError := strconv.Atoi(matches[1]); parseError == nil {
			return code, true
		}
	}
	return 0, false
}
