package service

import (
	"context"
	"io"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/k8s/podfs"
)

// PodFSService browses and edits the filesystem inside a running pod. Every
// operation is bounded by the configured size limits: these run `cat` and `tee`
// over an exec stream, so an unbounded file would be read into memory.
type PodFSService struct {
	services *Services
}

// FileContent is a read file, with the flag the editor needs to refuse a file
// it cannot safely round-trip.
type FileContent struct {
	Path     string `json:"path"`
	Content  string `json:"content"`
	Size     int64  `json:"size"`
	Editable bool   `json:"editable"`
}

func (s *PodFSService) apiServer(ctx context.Context, environmentName string) (*domain.KubernetesApiServer, error) {
	environment, err := s.services.environmentByName(ctx, environmentName)
	if err != nil {
		return nil, err
	}
	return environment.KubernetesApiServer, nil
}

func (s *PodFSService) List(ctx context.Context, environmentName, namespace, pod, container, path string) ([]podfs.Entry, error) {
	apiServer, err := s.apiServer(ctx, environmentName)
	if err != nil {
		return nil, err
	}
	return s.services.PodFiles.List(ctx, apiServer, namespace, pod, container, path)
}

// ReadFile returns a file's content, refusing one larger than the edit limit —
// the editor would not be able to save it back safely.
func (s *PodFSService) ReadFile(ctx context.Context, environmentName, namespace, pod, container, path string) (*FileContent, error) {
	apiServer, err := s.apiServer(ctx, environmentName)
	if err != nil {
		return nil, err
	}
	size, err := s.services.PodFiles.FileSize(ctx, apiServer, namespace, pod, container, path)
	if err != nil {
		return nil, err
	}
	limit := s.services.Config.PodFilesystem.MaxEditSizeBytes
	if limit > 0 && size > limit {
		return &FileContent{Path: path, Size: size, Editable: false}, nil
	}
	var buffer capped
	buffer.limit = limit
	if err := s.services.PodFiles.StreamFile(ctx, apiServer, namespace, pod, container, path, &buffer); err != nil {
		return nil, err
	}
	return &FileContent{Path: path, Content: buffer.String(), Size: size, Editable: true}, nil
}

// PrepareDownload checks the size limit and reports the byte count, so the
// caller can set Content-Length before a single byte is written. Splitting it
// from the streaming is what lets a failure still be reported as an envelope:
// once the body has started there is no way back.
func (s *PodFSService) PrepareDownload(ctx context.Context, environmentName, namespace, pod, container, path string) (int64, error) {
	apiServer, err := s.apiServer(ctx, environmentName)
	if err != nil {
		return 0, err
	}
	size, err := s.services.PodFiles.FileSize(ctx, apiServer, namespace, pod, container, path)
	if err != nil {
		return 0, err
	}
	if limit := s.services.Config.PodFilesystem.MaxDownloadSizeBytes; limit > 0 && size > limit {
		return 0, domain.Biz("File is too large to download")
	}
	return size, nil
}

// DownloadFile streams a file out. Call PrepareDownload first.
func (s *PodFSService) DownloadFile(ctx context.Context, environmentName, namespace, pod, container, path string, w io.Writer) error {
	apiServer, err := s.apiServer(ctx, environmentName)
	if err != nil {
		return err
	}
	return s.services.PodFiles.StreamFile(ctx, apiServer, namespace, pod, container, path, w)
}

// WriteFile replaces a file's content.
func (s *PodFSService) WriteFile(ctx context.Context, environmentName, namespace, pod, container, path string, content []byte) error {
	if limit := s.services.Config.PodFilesystem.MaxEditSizeBytes; limit > 0 && int64(len(content)) > limit {
		return domain.Biz("File is too large to save")
	}
	apiServer, err := s.apiServer(ctx, environmentName)
	if err != nil {
		return err
	}
	return s.services.PodFiles.Upload(ctx, apiServer, namespace, pod, container, path, content)
}

// UploadFile writes an uploaded file into the pod.
func (s *PodFSService) UploadFile(ctx context.Context, environmentName, namespace, pod, container, path string, content []byte) error {
	if limit := s.services.Config.PodFilesystem.MaxUploadSizeBytes; limit > 0 && int64(len(content)) > limit {
		return domain.Biz("File is too large to upload")
	}
	apiServer, err := s.apiServer(ctx, environmentName)
	if err != nil {
		return err
	}
	return s.services.PodFiles.Upload(ctx, apiServer, namespace, pod, container, path, content)
}

func (s *PodFSService) Delete(ctx context.Context, environmentName, namespace, pod, container, path string) error {
	apiServer, err := s.apiServer(ctx, environmentName)
	if err != nil {
		return err
	}
	return s.services.PodFiles.Delete(ctx, apiServer, namespace, pod, container, path)
}

func (s *PodFSService) CreateDirectory(ctx context.Context, environmentName, namespace, pod, container, path string) error {
	apiServer, err := s.apiServer(ctx, environmentName)
	if err != nil {
		return err
	}
	return s.services.PodFiles.CreateDirectory(ctx, apiServer, namespace, pod, container, path)
}

func (s *PodFSService) Rename(ctx context.Context, environmentName, namespace, pod, container, from, to string) error {
	apiServer, err := s.apiServer(ctx, environmentName)
	if err != nil {
		return err
	}
	return s.services.PodFiles.Rename(ctx, apiServer, namespace, pod, container, from, to)
}

// capped collects a bounded amount of a stream. The size check already ran, so
// this only guards against a file that grew between the check and the read.
type capped struct {
	data  []byte
	limit int64
}

func (c *capped) Write(chunk []byte) (int, error) {
	if c.limit > 0 && int64(len(c.data)) >= c.limit {
		return len(chunk), nil
	}
	c.data = append(c.data, chunk...)
	return len(chunk), nil
}

func (c *capped) String() string { return string(c.data) }
