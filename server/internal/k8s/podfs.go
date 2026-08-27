package k8s

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/client-go/kubernetes/scheme"
	"k8s.io/client-go/tools/remotecommand"
)

// The scripts and error sentinels are shared verbatim with
// KubernetesPodFileSystemGateway so both sides behave identically.
const (
	listScript = `set -e
target=$1
if [ -z "$target" ]; then target=/; fi
if [ ! -e "$target" ] && [ ! -L "$target" ]; then echo __OOPS_NOT_FOUND__; exit 0; fi
if [ ! -d "$target/" ]; then echo __OOPS_NOT_DIR__; exit 0; fi
cd "$target" 2>/dev/null || { echo __OOPS_DENIED__; exit 0; }
for entry in .* *; do
  [ "$entry" = "." ] && continue
  [ "$entry" = ".." ] && continue
  [ "$entry" = "*" ] && continue
  [ "$entry" = ".*" ] && continue
  [ ! -e "$entry" ] && [ ! -L "$entry" ] && continue
  if [ -L "$entry" ]; then
    if [ -d "$entry/" ]; then
      kind=LD; size=0
    elif [ -f "$entry" ]; then
      kind=LF
      size=$(wc -c < "$entry" 2>/dev/null | tr -d ' ' || echo 0)
    else
      kind=O; size=0
    fi
  elif [ -d "$entry" ]; then
    kind=D; size=0
  elif [ -f "$entry" ]; then
    kind=F
    size=$(wc -c < "$entry" 2>/dev/null | tr -d ' ' || echo 0)
  else
    kind=O; size=0
  fi
  printf '%s\t%s\t%s\n' "$kind" "$size" "$entry"
done
`
	fileSizeScript = `target=$1
if [ ! -f "$target" ]; then echo __OOPS_NOT_FILE__ >&2; exit 1; fi
wc -c < "$target" 2>/dev/null | tr -d ' '
`
	catFileScript = `target=$1
if [ ! -f "$target" ]; then echo __OOPS_NOT_FILE__ >&2; exit 1; fi
cat "$target"
`
	deleteScript = `target=$1
if [ -z "$target" ] || [ "$target" = "/" ]; then echo __OOPS_REFUSED__ >&2; exit 1; fi
if [ ! -e "$target" ] && [ ! -L "$target" ]; then echo __OOPS_NOT_FOUND__ >&2; exit 1; fi
rm -rf -- "$target" 2>&1 || { echo __OOPS_DELETE_FAILED__ >&2; exit 1; }
`
	mkdirScript = `target=$1
if [ -z "$target" ] || [ "$target" = "/" ]; then echo __OOPS_REFUSED__ >&2; exit 1; fi
if [ -e "$target" ] || [ -L "$target" ]; then echo __OOPS_TARGET_EXISTS__ >&2; exit 1; fi
mkdir -p -- "$target" || { echo __OOPS_MKDIR_FAILED__ >&2; exit 1; }
`
	renameScript = `from=$1
to=$2
if [ -z "$from" ] || [ -z "$to" ] || [ "$from" = "/" ] || [ "$to" = "/" ]; then echo __OOPS_REFUSED__ >&2; exit 1; fi
if [ ! -e "$from" ] && [ ! -L "$from" ]; then echo __OOPS_NOT_FOUND__ >&2; exit 1; fi
if [ -e "$to" ] || [ -L "$to" ]; then echo __OOPS_TARGET_EXISTS__ >&2; exit 1; fi
mv -- "$from" "$to" 2>&1 || { echo __OOPS_RENAME_FAILED__ >&2; exit 1; }
`
	writeFileScript = `target=$1
if [ -z "$target" ] || [ "$target" = "/" ]; then echo __OOPS_REFUSED__ >&2; exit 1; fi
directory=$(dirname -- "$target")
mkdir -p -- "$directory" 2>/dev/null || true
cat > "$target" || { echo __OOPS_WRITE_FAILED__ >&2; exit 1; }
`
)

type podFSError struct{ message string }

func (e *podFSError) Error() string { return e.message }

// IsPodFSError reports a user-facing pod filesystem failure.
func IsPodFSError(err error) bool {
	_, matches := err.(*podFSError)
	return matches
}

func podFSErrorf(format string, args ...any) error {
	return &podFSError{message: fmt.Sprintf(format, args...)}
}

func sanitizePodPath(path string) error {
	if path == "" || strings.ContainsAny(path, "\x00\n") {
		return podFSErrorf("Invalid path")
	}
	return nil
}

// execScript runs a shell script (with positional args) inside the container,
// capturing stdout/stderr; stdin feeds uploads and file writes; streamOut, when
// set, receives stdout directly (downloads).
func execScript(ctx context.Context, cluster *Cluster, namespace, pod, container, script string,
	args []string, stdin io.Reader, streamOut io.Writer, timeout time.Duration) (string, string, error) {

	command := append([]string{"sh", "-c", script + "\n", "sh"}, args...)
	request := cluster.Clientset.CoreV1().RESTClient().Post().
		Resource("pods").Namespace(namespace).Name(pod).SubResource("exec").
		VersionedParams(&corev1.PodExecOptions{
			Container: container,
			Command:   command,
			Stdin:     stdin != nil,
			Stdout:    true,
			Stderr:    true,
		}, scheme.ParameterCodec)
	executor, err := remotecommand.NewSPDYExecutor(cluster.Config, http.MethodPost, request.URL())
	if err != nil {
		return "", "", err
	}
	var stdout, stderr bytes.Buffer
	out := io.Writer(&stdout)
	if streamOut != nil {
		out = streamOut
	}
	execContext, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	streamError := executor.StreamWithContext(execContext, remotecommand.StreamOptions{
		Stdin: stdin, Stdout: out, Stderr: &stderr,
	})
	return stdout.String(), stderr.String(), streamError
}

// PodFileEntry mirrors the Java record.
type PodFileEntry struct {
	Name string `json:"name"`
	Path string `json:"path"`
	Type string `json:"type"`
	Size *int64 `json:"size"`
}

func ListPodDirectory(ctx context.Context, cluster *Cluster, namespace, pod, container, path string) ([]PodFileEntry, error) {
	if path == "" {
		path = "/"
	}
	if err := sanitizePodPath(path); err != nil {
		return nil, err
	}
	stdout, _, _ := execScript(ctx, cluster, namespace, pod, container, listScript, []string{path}, nil, nil, 15*time.Second)
	if len(stdout) > 1_048_576 {
		return nil, podFSErrorf("Directory listing too large")
	}
	switch {
	case strings.Contains(stdout, "__OOPS_NOT_FOUND__"):
		return nil, podFSErrorf("Path not found")
	case strings.Contains(stdout, "__OOPS_NOT_DIR__"):
		return nil, podFSErrorf("Not a directory")
	case strings.Contains(stdout, "__OOPS_DENIED__"):
		return nil, podFSErrorf("Permission denied")
	}
	entries := []PodFileEntry{}
	for _, line := range strings.Split(stdout, "\n") {
		parts := strings.SplitN(line, "\t", 3)
		if len(parts) < 3 {
			continue
		}
		entryType := map[string]string{
			"D": "DIRECTORY", "F": "FILE", "LD": "SYMLINK_DIRECTORY", "LF": "SYMLINK_FILE",
		}[parts[0]]
		if entryType == "" {
			entryType = "OTHER"
		}
		var size *int64
		if parsed, err := strconv.ParseInt(parts[1], 10, 64); err == nil {
			size = &parsed
		}
		name := parts[2]
		fullPath := path + "/" + name
		if strings.HasSuffix(path, "/") {
			fullPath = path + name
		}
		entries = append(entries, PodFileEntry{Name: name, Path: fullPath, Type: entryType, Size: size})
	}
	sort.SliceStable(entries, func(i, j int) bool {
		isDirectory := func(entry PodFileEntry) bool {
			return entry.Type == "DIRECTORY" || entry.Type == "SYMLINK_DIRECTORY"
		}
		if isDirectory(entries[i]) != isDirectory(entries[j]) {
			return isDirectory(entries[i])
		}
		return strings.ToLower(entries[i].Name) < strings.ToLower(entries[j].Name)
	})
	return entries, nil
}

func GetPodFileSize(ctx context.Context, cluster *Cluster, namespace, pod, container, path string) (int64, error) {
	if err := sanitizePodPath(path); err != nil {
		return 0, err
	}
	stdout, stderr, _ := execScript(ctx, cluster, namespace, pod, container, fileSizeScript, []string{path}, nil, nil, 15*time.Second)
	if strings.Contains(stderr, "__OOPS_NOT_FILE__") {
		return 0, podFSErrorf("Not a file")
	}
	size, err := strconv.ParseInt(strings.TrimSpace(stdout), 10, 64)
	if err != nil {
		return 0, podFSErrorf("Get file size failed")
	}
	return size, nil
}

func StreamPodFile(ctx context.Context, cluster *Cluster, namespace, pod, container, path string, out io.Writer) error {
	if err := sanitizePodPath(path); err != nil {
		return err
	}
	_, stderr, err := execScript(ctx, cluster, namespace, pod, container, catFileScript, []string{path}, nil, out, 120*time.Second)
	if strings.Contains(stderr, "__OOPS_NOT_FILE__") {
		return podFSErrorf("Not a file")
	}
	return err
}

func ReadPodTextFile(ctx context.Context, cluster *Cluster, namespace, pod, container, path string, maxBytes int64) (string, error) {
	size, err := GetPodFileSize(ctx, cluster, namespace, pod, container, path)
	if err != nil {
		return "", err
	}
	if size > maxBytes {
		return "", podFSErrorf("File too large to edit: %d bytes (max %d MB)", size, maxBytes/(1024*1024))
	}
	var buffer bytes.Buffer
	if err := StreamPodFile(ctx, cluster, namespace, pod, container, path, &buffer); err != nil {
		return "", err
	}
	return buffer.String(), nil
}

func WritePodTextFile(ctx context.Context, cluster *Cluster, namespace, pod, container, path, content string) error {
	if err := sanitizePodPath(path); err != nil {
		return err
	}
	_, stderr, err := execScript(ctx, cluster, namespace, pod, container, writeFileScript, []string{path},
		strings.NewReader(content), nil, 120*time.Second)
	switch {
	case strings.Contains(stderr, "__OOPS_REFUSED__"):
		return podFSErrorf("Refusing to write to root path")
	case strings.Contains(stderr, "__OOPS_WRITE_FAILED__"):
		return podFSErrorf("Write failed")
	}
	return err
}

func UploadPodFile(ctx context.Context, cluster *Cluster, namespace, pod, container, path string, content io.Reader) error {
	if err := sanitizePodPath(path); err != nil {
		return err
	}
	if path == "/" {
		return podFSErrorf("Refusing to upload to root path")
	}
	_, stderr, err := execScript(ctx, cluster, namespace, pod, container, writeFileScript, []string{path},
		content, nil, 600*time.Second)
	if strings.Contains(stderr, "__OOPS_WRITE_FAILED__") {
		return podFSErrorf("Upload failed")
	}
	return err
}

func runSentinelScript(ctx context.Context, cluster *Cluster, namespace, pod, container, script string, args []string, failures map[string]string) error {
	for _, argument := range args {
		if err := sanitizePodPath(argument); err != nil {
			return err
		}
	}
	_, stderr, err := execScript(ctx, cluster, namespace, pod, container, script, args, nil, nil, 15*time.Second)
	for sentinel, message := range failures {
		if strings.Contains(stderr, sentinel) {
			return podFSErrorf("%s", message)
		}
	}
	return err
}

func DeletePodPath(ctx context.Context, cluster *Cluster, namespace, pod, container, path string) error {
	return runSentinelScript(ctx, cluster, namespace, pod, container, deleteScript, []string{path}, map[string]string{
		"__OOPS_REFUSED__":       "Refusing to delete this path",
		"__OOPS_NOT_FOUND__":     "Path not found",
		"__OOPS_DELETE_FAILED__": "Delete failed",
	})
}

func CreatePodDirectory(ctx context.Context, cluster *Cluster, namespace, pod, container, path string) error {
	return runSentinelScript(ctx, cluster, namespace, pod, container, mkdirScript, []string{path}, map[string]string{
		"__OOPS_REFUSED__":       "Refusing to create this path",
		"__OOPS_TARGET_EXISTS__": "Target already exists",
		"__OOPS_MKDIR_FAILED__":  "Create directory failed",
	})
}

func RenamePodPath(ctx context.Context, cluster *Cluster, namespace, pod, container, fromPath, toPath string) error {
	return runSentinelScript(ctx, cluster, namespace, pod, container, renameScript, []string{fromPath, toPath}, map[string]string{
		"__OOPS_REFUSED__":       "Refusing to rename this path",
		"__OOPS_NOT_FOUND__":     "Path not found",
		"__OOPS_TARGET_EXISTS__": "Target already exists",
		"__OOPS_RENAME_FAILED__": "Rename failed",
	})
}
