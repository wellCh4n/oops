package httpapi

import (
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"path"
	"strconv"
	"strings"

	"github.com/wellch4n/oops/server/internal/service"
)

// maxInlineUpload caps a multipart upload read into memory. The pod filesystem
// writes through an exec stream, so the whole body has to be buffered anyway.
const maxInlineUpload = 64 << 20

// ---------------------------------------------------------------------------
// sandbox

func (s *Server) sandboxImages(w http.ResponseWriter, r *http.Request) {
	OK(w, s.services.Sandboxes.Images())
}

func (s *Server) sandboxExecute(w http.ResponseWriter, r *http.Request) {
	var request service.ExecutionRequest
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	// A streaming caller asks for it in the body; everyone else waits for the
	// finished result.
	if request.Stream != nil && *request.Stream {
		s.streamSandboxExecution(w, r, request)
		return
	}
	result, err := s.services.Sandboxes.Execute(r.Context(), request, callerOf(r))
	Respond(w, r, result, err)
}

// streamSandboxExecution reports output over SSE as the job produces it.
func (s *Server) streamSandboxExecution(w http.ResponseWriter, r *http.Request, request service.ExecutionRequest) {
	stream, ok := newSSE(w)
	if !ok {
		Fail(w, "Streaming is not supported by this connection")
		return
	}
	err := s.services.Sandboxes.Stream(r.Context(), request, callerOf(r),
		func(line string) { _ = stream.Event("output", line) },
		func(code int) { _ = stream.EventJSON("exit", map[string]int{"exitCode": code}) })
	if err != nil {
		_ = stream.Event("error", err.Error())
	}
}

func (s *Server) createSandboxInstance(w http.ResponseWriter, r *http.Request) {
	var request service.InstanceRequest
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	instance, err := s.services.Sandboxes.CreateInstance(r.Context(), request, callerOf(r))
	Respond(w, r, instance, err)
}

func (s *Server) listSandboxInstances(w http.ResponseWriter, r *http.Request) {
	instances, err := s.services.Sandboxes.ListInstances(r.Context(), environmentOf(r), Query(r, "image"))
	Respond(w, r, EmptyIfNil(instances), err)
}

func (s *Server) getSandboxInstance(w http.ResponseWriter, r *http.Request) {
	instance, err := s.services.Sandboxes.GetInstance(r.Context(), Param(r, "id"))
	Respond(w, r, instance, err)
}

// deleteSandboxInstance answers with a null payload, like the Java endpoint: the
// caller has nothing to do with a body here.
func (s *Server) deleteSandboxInstance(w http.ResponseWriter, r *http.Request) {
	Respond(w, r, nil, s.services.Sandboxes.DeleteInstance(r.Context(), Param(r, "id")))
}

func (s *Server) execSandboxInstance(w http.ResponseWriter, r *http.Request) {
	var request service.InstanceExecRequest
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	if request.Stream != nil && *request.Stream {
		stream, ok := newSSE(w)
		if !ok {
			Fail(w, "Streaming is not supported by this connection")
			return
		}
		if err := s.services.Sandboxes.StreamExecInstance(r.Context(), Param(r, "id"), request,
			func(line string) { _ = stream.Event("output", line) },
			func(code int) { _ = stream.EventJSON("exit", map[string]int{"exitCode": code}) }); err != nil {
			_ = stream.Event("error", err.Error())
		}
		return
	}
	result, err := s.services.Sandboxes.ExecInstance(r.Context(), Param(r, "id"), request)
	Respond(w, r, result, err)
}

// sandboxTarget resolves a sandbox's pod so the filesystem handlers can address
// it exactly like an application pod. Unlike an application pod it names a
// container, because a sandbox pod's is not called after the application.
func (s *Server) sandboxTarget(r *http.Request) (environmentName, namespace, pod, container string, err error) {
	return s.services.Sandboxes.InstanceTarget(r.Context(), Param(r, "id"))
}

func (s *Server) listSandboxFiles(w http.ResponseWriter, r *http.Request) {
	environmentName, namespace, pod, container, err := s.sandboxTarget(r)
	if err != nil {
		Error(w, r, err)
		return
	}
	entries, err := s.services.PodFS.List(r.Context(), environmentName, namespace, pod, container, filePathOf(r))
	Respond(w, r, EmptyIfNil(entries), err)
}

func (s *Server) readSandboxFile(w http.ResponseWriter, r *http.Request) {
	environmentName, namespace, pod, container, err := s.sandboxTarget(r)
	if err != nil {
		Error(w, r, err)
		return
	}
	content, err := s.services.PodFS.ReadFile(r.Context(), environmentName, namespace, pod, container, filePathOf(r))
	Respond(w, r, content, err)
}

func (s *Server) writeSandboxFile(w http.ResponseWriter, r *http.Request) {
	environmentName, namespace, pod, container, err := s.sandboxTarget(r)
	if err != nil {
		Error(w, r, err)
		return
	}
	var request struct {
		Path    string `json:"path"`
		Content string `json:"content"`
	}
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	err = s.services.PodFS.WriteFile(r.Context(), environmentName, namespace, pod, container, request.Path, []byte(request.Content))
	Respond(w, r, true, err)
}

func (s *Server) downloadSandboxFile(w http.ResponseWriter, r *http.Request) {
	environmentName, namespace, pod, container, err := s.sandboxTarget(r)
	if err != nil {
		Error(w, r, err)
		return
	}
	s.streamDownload(w, r, environmentName, namespace, pod, container, filePathOf(r))
}

func (s *Server) uploadSandboxFile(w http.ResponseWriter, r *http.Request) {
	environmentName, namespace, pod, container, err := s.sandboxTarget(r)
	if err != nil {
		Error(w, r, err)
		return
	}
	s.handleUpload(w, r, environmentName, namespace, pod, container)
}

func (s *Server) deleteSandboxFile(w http.ResponseWriter, r *http.Request) {
	environmentName, namespace, pod, container, err := s.sandboxTarget(r)
	if err != nil {
		Error(w, r, err)
		return
	}
	Respond(w, r, true, s.services.PodFS.Delete(r.Context(), environmentName, namespace, pod, container, filePathOf(r)))
}

func (s *Server) createSandboxDirectory(w http.ResponseWriter, r *http.Request) {
	environmentName, namespace, pod, container, err := s.sandboxTarget(r)
	if err != nil {
		Error(w, r, err)
		return
	}
	s.handleCreateDirectory(w, r, environmentName, namespace, pod, container)
}

func (s *Server) renameSandboxFile(w http.ResponseWriter, r *http.Request) {
	environmentName, namespace, pod, container, err := s.sandboxTarget(r)
	if err != nil {
		Error(w, r, err)
		return
	}
	s.handleRename(w, r, environmentName, namespace, pod, container)
}

// ---------------------------------------------------------------------------
// pod filesystem (application pods)

// filePathOf reads the `path` query parameter, defaulting to the root.
func filePathOf(r *http.Request) string {
	value := Query(r, "path")
	if value == "" {
		return "/"
	}
	return value
}

// podTarget reads an application pod's coordinates. The container is optional:
// omitted, it defaults to the one named after the application.
func (s *Server) podTarget(r *http.Request) (environmentName, namespace, pod, container string) {
	return environmentOf(r), Param(r, "namespace"), Param(r, "pod"), Query(r, "container")
}

func (s *Server) listPodFiles(w http.ResponseWriter, r *http.Request) {
	environmentName, namespace, pod, container := s.podTarget(r)
	entries, err := s.services.PodFS.List(r.Context(), environmentName, namespace, pod, container, filePathOf(r))
	Respond(w, r, EmptyIfNil(entries), err)
}

func (s *Server) readPodFile(w http.ResponseWriter, r *http.Request) {
	environmentName, namespace, pod, container := s.podTarget(r)
	content, err := s.services.PodFS.ReadFile(r.Context(), environmentName, namespace, pod, container, filePathOf(r))
	Respond(w, r, content, err)
}

func (s *Server) writePodFile(w http.ResponseWriter, r *http.Request) {
	environmentName, namespace, pod, container := s.podTarget(r)
	var request struct {
		Path    string `json:"path"`
		Content string `json:"content"`
	}
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	err := s.services.PodFS.WriteFile(r.Context(), environmentName, namespace, pod, container, request.Path, []byte(request.Content))
	Respond(w, r, true, err)
}

func (s *Server) downloadPodFile(w http.ResponseWriter, r *http.Request) {
	environmentName, namespace, pod, container := s.podTarget(r)
	s.streamDownload(w, r, environmentName, namespace, pod, container, filePathOf(r))
}

func (s *Server) uploadPodFile(w http.ResponseWriter, r *http.Request) {
	environmentName, namespace, pod, container := s.podTarget(r)
	s.handleUpload(w, r, environmentName, namespace, pod, container)
}

func (s *Server) deletePodFile(w http.ResponseWriter, r *http.Request) {
	environmentName, namespace, pod, container := s.podTarget(r)
	Respond(w, r, true, s.services.PodFS.Delete(r.Context(), environmentName, namespace, pod, container, filePathOf(r)))
}

func (s *Server) createPodDirectory(w http.ResponseWriter, r *http.Request) {
	environmentName, namespace, pod, container := s.podTarget(r)
	s.handleCreateDirectory(w, r, environmentName, namespace, pod, container)
}

func (s *Server) renamePodFile(w http.ResponseWriter, r *http.Request) {
	environmentName, namespace, pod, container := s.podTarget(r)
	s.handleRename(w, r, environmentName, namespace, pod, container)
}

// ---------------------------------------------------------------------------
// shared filesystem handling

// streamDownload writes the file straight to the response rather than buffering
// it, so a large download costs the server no memory.
//
// The size is resolved first: a browser needs Content-Length to show progress
// and to know the transfer completed, and anything that can fail has to fail
// before the first byte, while an error can still be an envelope.
func (s *Server) streamDownload(w http.ResponseWriter, r *http.Request, environmentName, namespace, pod, container, filePath string) {
	size, err := s.services.PodFS.PrepareDownload(r.Context(), environmentName, namespace, pod, container, filePath)
	if err != nil {
		Error(w, r, err)
		return
	}
	name := path.Base(filePath)
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.FormatInt(size, 10))
	// Both forms: the plain one for anything that does not understand RFC 5987,
	// and the encoded one, which is what browsers actually read and the only one
	// that survives a non-ASCII filename.
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename=%q; filename*=UTF-8''%s`,
		name, url.PathEscape(name)))
	if err := s.services.PodFS.DownloadFile(r.Context(), environmentName, namespace, pod, container, filePath, w); err != nil {
		// The body has already started, so this can only be logged — the client
		// sees a short read against the Content-Length it was promised.
		slog.Error("pod file download failed mid-stream",
			"namespace", namespace, "pod", pod, "path", filePath, "error", err)
	}
}

func (s *Server) handleUpload(w http.ResponseWriter, r *http.Request, environmentName, namespace, pod, container string) {
	if err := r.ParseMultipartForm(maxInlineUpload); err != nil {
		Error(w, r, ErrBadRequest)
		return
	}
	file, header, err := r.FormFile("file")
	if err != nil {
		Fail(w, "File is required")
		return
	}
	defer file.Close()
	content, err := io.ReadAll(io.LimitReader(file, maxInlineUpload))
	if err != nil {
		Error(w, r, err)
		return
	}
	// The form's path is the directory; the file keeps the name it was uploaded
	// under unless the caller named one.
	target := strings.TrimSpace(r.FormValue("path"))
	if target == "" || strings.HasSuffix(target, "/") {
		target = strings.TrimSuffix(target, "/") + "/" + header.Filename
	}
	err = s.services.PodFS.UploadFile(r.Context(), environmentName, namespace, pod, container, target, content)
	Respond(w, r, true, err)
}

func (s *Server) handleCreateDirectory(w http.ResponseWriter, r *http.Request, environmentName, namespace, pod, container string) {
	var request struct {
		Path string `json:"path"`
	}
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	err := s.services.PodFS.CreateDirectory(r.Context(), environmentName, namespace, pod, container, request.Path)
	Respond(w, r, true, err)
}

func (s *Server) handleRename(w http.ResponseWriter, r *http.Request, environmentName, namespace, pod, container string) {
	var request struct {
		FromPath string `json:"fromPath"`
		ToPath   string `json:"toPath"`
	}
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	err := s.services.PodFS.Rename(r.Context(), environmentName, namespace, pod, container, request.FromPath, request.ToPath)
	Respond(w, r, true, err)
}
