// Package podfs is the pod filesystem browser: list, read, write, upload,
// delete, mkdir and rename inside a running container, all implemented as
// small POSIX shell scripts run through the Kubernetes exec sub-resource.
package podfs

import (
	"context"
	"io"
	"log/slog"
	"strconv"
	"strings"
	"time"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/k8s"
)

const (
	execTimeout          = 15 * time.Second
	downloadTimeout      = 120 * time.Second
	uploadRequestTimeout = 600 * time.Second
	uploadMaxAttempts    = 3
	uploadRetryBackoff   = 500 * time.Millisecond
	maxOutputBytes       = 1_048_576
)

// FileType classifies a directory entry.
type FileType string

const (
	FileTypeDirectory        FileType = "DIRECTORY"
	FileTypeFile             FileType = "FILE"
	FileTypeSymlinkDirectory FileType = "SYMLINK_DIRECTORY"
	FileTypeSymlinkFile      FileType = "SYMLINK_FILE"
	FileTypeOther            FileType = "OTHER"
)

// IsDirectory reports whether the entry can be descended into.
func (t FileType) IsDirectory() bool {
	return t == FileTypeDirectory || t == FileTypeSymlinkDirectory
}

// Entry is one directory listing row (PodFileEntry on the wire).
type Entry struct {
	Name string   `json:"name"`
	Path string   `json:"path"`
	Type FileType `json:"type"`
	Size *int64   `json:"size"`
}

// Gateway runs filesystem operations inside pods using the pooled client.
type Gateway struct {
	pool *k8s.Pool
}

// New creates a Gateway over the shared client pool.
func New(pool *k8s.Pool) *Gateway {
	return &Gateway{pool: pool}
}

// Scripts are copied verbatim from KubernetesPodFileSystemGateway. Every exec
// runs `sh -c "<script>\n" sh <args...>` so $1/$2 are the positional paths.
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
	uploadScript = `mkdir -p "$(dirname "$1")" && cat > "$1"`
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
)

// List returns the entries of a directory; a blank path lists "/".
func (g *Gateway) List(ctx context.Context, apiServer *domain.KubernetesApiServer, namespace, pod, container, path string) ([]Entry, error) {
	normalizedPath := path
	if strings.TrimSpace(normalizedPath) == "" {
		normalizedPath = "/"
	}
	if strings.ContainsAny(normalizedPath, "\x00\n") {
		return nil, domain.Biz("Invalid path")
	}

	result, err := g.run(ctx, target{apiServer, namespace, pod, container}, execRequest{
		script:           listScript,
		args:             []string{normalizedPath},
		timeout:          execTimeout,
		timeoutMessage:   "Listing directory timed out",
		interruptMessage: "Listing directory interrupted",
	})
	if err != nil {
		return nil, err
	}
	if result.stdout.overflowed() {
		return nil, domain.Biz("Directory listing too large")
	}
	return parseListing(result.stdout.String(), normalizedPath)
}

// FileSize returns the byte size of a regular file.
func (g *Gateway) FileSize(ctx context.Context, apiServer *domain.KubernetesApiServer, namespace, pod, container, path string) (int64, error) {
	if err := sanitizePath(path); err != nil {
		return 0, err
	}
	result, err := g.run(ctx, target{apiServer, namespace, pod, container}, execRequest{
		script:           fileSizeScript,
		args:             []string{path},
		timeout:          execTimeout,
		timeoutMessage:   "Get file size timed out",
		interruptMessage: "Get file size interrupted",
	})
	if err != nil {
		return 0, err
	}
	stderr := strings.TrimSpace(result.stderr.String())
	if result.failed() {
		return 0, domain.Biz("Get file size failed")
	}
	if strings.Contains(stderr, "__OOPS_NOT_FILE__") {
		return 0, domain.Biz("Not a file")
	}
	stdout := strings.TrimSpace(result.stdout.String())
	if strings.Contains(stdout, "__OOPS_NOT_FILE__") {
		return 0, domain.Biz("Not a file")
	}
	size, parseErr := strconv.ParseInt(stdout, 10, 64)
	if parseErr != nil {
		return 0, domain.Biz("Failed to parse file size")
	}
	return size, nil
}

// StreamFile writes the file's bytes straight to w.
func (g *Gateway) StreamFile(ctx context.Context, apiServer *domain.KubernetesApiServer, namespace, pod, container, path string, w io.Writer) error {
	if err := sanitizePath(path); err != nil {
		return err
	}
	result, err := g.run(ctx, target{apiServer, namespace, pod, container}, execRequest{
		script:           catFileScript,
		args:             []string{path},
		timeout:          downloadTimeout,
		timeoutMessage:   "Download timed out",
		interruptMessage: "Download interrupted",
		stdout:           w,
	})
	if err != nil {
		return err
	}
	if result.failed() {
		return domain.Biz("Download failed")
	}
	if strings.Contains(result.stderr.String(), "__OOPS_NOT_FILE__") {
		return domain.Biz("File not found")
	}
	return nil
}

// Upload writes content to path, creating parent directories. It retries up
// to three times with a 500ms*attempt backoff.
func (g *Gateway) Upload(ctx context.Context, apiServer *domain.KubernetesApiServer, namespace, pod, container, path string, content []byte) error {
	if err := sanitizePath(path); err != nil {
		return err
	}
	if path == "/" {
		return domain.Biz("Refusing to upload to root path")
	}

	var lastFailure error
	for attempt := 1; attempt <= uploadMaxAttempts; attempt++ {
		result, err := g.run(ctx, target{apiServer, namespace, pod, container}, execRequest{
			script:           uploadScript,
			args:             []string{path},
			timeout:          uploadRequestTimeout,
			timeoutMessage:   "Upload timed out",
			interruptMessage: "Upload interrupted",
			stdin:            content,
		})
		switch {
		case err != nil && ctx.Err() != nil:
			return domain.Biz("Upload interrupted")
		case err != nil:
			lastFailure = err
		case result.failed():
			lastFailure = result.failure
		case result.exitCode != 0:
			lastFailure = domain.Bizf("exit status %d: %s", result.exitCode, strings.TrimSpace(result.stderr.String()))
		default:
			return nil
		}
		slog.Warn("Upload attempt failed", "attempt", attempt, "maxAttempts", uploadMaxAttempts,
			"pod", namespace+"/"+pod, "path", path, "error", lastFailure)

		if attempt < uploadMaxAttempts {
			timer := time.NewTimer(uploadRetryBackoff * time.Duration(attempt))
			select {
			case <-ctx.Done():
				timer.Stop()
				return domain.Biz("Upload interrupted")
			case <-timer.C:
			}
		}
	}
	if lastFailure != nil {
		return domain.Biz("Upload failed: " + lastFailure.Error())
	}
	return domain.Bizf("Upload failed after %d attempts", uploadMaxAttempts)
}

// Delete removes a file or directory tree.
func (g *Gateway) Delete(ctx context.Context, apiServer *domain.KubernetesApiServer, namespace, pod, container, path string) error {
	if err := sanitizePath(path); err != nil {
		return err
	}
	if path == "/" {
		return domain.Biz("Refusing to delete root path")
	}
	result, err := g.runGeneric(ctx, target{apiServer, namespace, pod, container}, deleteScript, path)
	if err != nil {
		return err
	}
	stderr := result.stderr.String()
	switch {
	case strings.Contains(stderr, "__OOPS_REFUSED__"):
		return domain.Biz("Refused to delete path")
	case strings.Contains(stderr, "__OOPS_NOT_FOUND__"):
		return domain.Biz("Path not found")
	case result.failed() || strings.Contains(stderr, "__OOPS_DELETE_FAILED__"):
		return domain.Biz("Delete failed")
	}
	return nil
}

// CreateDirectory creates a directory (and missing parents).
func (g *Gateway) CreateDirectory(ctx context.Context, apiServer *domain.KubernetesApiServer, namespace, pod, container, path string) error {
	if err := sanitizePath(path); err != nil {
		return err
	}
	if path == "/" {
		return domain.Biz("Refusing to create root path")
	}
	result, err := g.runGeneric(ctx, target{apiServer, namespace, pod, container}, mkdirScript, path)
	if err != nil {
		return err
	}
	stderr := result.stderr.String()
	switch {
	case strings.Contains(stderr, "__OOPS_REFUSED__"):
		return domain.Biz("Refused to create directory")
	case strings.Contains(stderr, "__OOPS_TARGET_EXISTS__"):
		return domain.Biz("Path already exists")
	case result.failed() || strings.Contains(stderr, "__OOPS_MKDIR_FAILED__"):
		detail := strings.TrimSpace(strings.ReplaceAll(stderr, "__OOPS_MKDIR_FAILED__", ""))
		if detail == "" {
			return domain.Biz("Create directory failed")
		}
		return domain.Biz("Create directory failed: " + detail)
	}
	return nil
}

// Rename moves from to to; the target must not exist.
func (g *Gateway) Rename(ctx context.Context, apiServer *domain.KubernetesApiServer, namespace, pod, container, from, to string) error {
	if err := sanitizePath(from); err != nil {
		return err
	}
	if err := sanitizePath(to); err != nil {
		return err
	}
	if from == "/" || to == "/" {
		return domain.Biz("Refusing to rename root path")
	}
	result, err := g.runGeneric(ctx, target{apiServer, namespace, pod, container}, renameScript, from, to)
	if err != nil {
		return err
	}
	stderr := result.stderr.String()
	switch {
	case strings.Contains(stderr, "__OOPS_REFUSED__"):
		return domain.Biz("Refused to rename path")
	case strings.Contains(stderr, "__OOPS_NOT_FOUND__"):
		return domain.Biz("Source path not found")
	case strings.Contains(stderr, "__OOPS_TARGET_EXISTS__"):
		return domain.Biz("Target path already exists")
	case result.failed() || strings.Contains(stderr, "__OOPS_RENAME_FAILED__"):
		return domain.Biz("Rename failed")
	}
	return nil
}

func sanitizePath(path string) error {
	if strings.TrimSpace(path) == "" {
		return domain.Biz("Path is required")
	}
	if strings.ContainsAny(path, "\x00\n") {
		return domain.Biz("Invalid path")
	}
	return nil
}
