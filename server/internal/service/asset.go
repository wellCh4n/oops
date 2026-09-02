package service

import (
	"context"
	"mime"
	"path/filepath"
	"strings"
	"time"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/objectstorage"
)

// AssetService is the object-storage-backed file browser: a flat bucket
// presented as folders, under one key prefix that keeps assets away from the
// build sources sharing the bucket.
type AssetService struct {
	services *Services
}

// AssetEntry is one row of the browser, a folder or a file.
type AssetEntry struct {
	Type         string     `json:"type"` // FOLDER or FILE
	Name         string     `json:"name"`
	Key          string     `json:"key"`
	Size         int64      `json:"size"`
	LastModified *time.Time `json:"lastModified"`
	ContentType  *string    `json:"contentType"`
	PublicURL    *string    `json:"publicUrl"`
	SignedURL    *string    `json:"signedUrl"`
}

// AssetUploadCommand is the request for an upload URL.
type AssetUploadCommand struct {
	Path        string `json:"path"`
	FileName    string `json:"fileName"`
	ContentType string `json:"contentType"`
	FileSize    *int64 `json:"fileSize"`
}

// assetPrefix is the configured key prefix without surrounding slashes.
func (s *AssetService) assetPrefix() string {
	prefix := strings.Trim(s.services.Storage.AssetKeyPrefix(), "/")
	if prefix == "" {
		prefix = "oops-assets"
	}
	return prefix
}

// dirPrefix turns a browser path into the object key prefix it lists.
func (s *AssetService) dirPrefix(path string) (string, error) {
	normalized := strings.Trim(strings.TrimSpace(path), "/")
	if strings.Contains(normalized, "..") {
		return "", domain.Biz("Invalid path")
	}
	if normalized == "" {
		return s.assetPrefix() + "/", nil
	}
	return s.assetPrefix() + "/" + normalized + "/", nil
}

// List returns the folders and files directly under path.
func (s *AssetService) List(ctx context.Context, path string) ([]AssetEntry, error) {
	prefix, err := s.dirPrefix(path)
	if err != nil {
		return nil, err
	}
	folders, files, err := s.services.Storage.ListDirectory(ctx, prefix)
	if err != nil {
		return nil, err
	}
	entries := make([]AssetEntry, 0, len(folders)+len(files))
	for _, folder := range folders {
		entries = append(entries, AssetEntry{Type: "FOLDER", Name: lastSegment(folder), Key: folder})
	}
	for _, file := range files {
		// The prefix itself and any explicit folder marker are not files.
		if file.Key == prefix || strings.HasSuffix(file.Key, "/") {
			continue
		}
		name := lastSegment(file.Key)
		entry := AssetEntry{
			Type: "FILE", Name: name, Key: file.Key, Size: file.Size,
			ContentType: domain.StringOrNil(guessContentType(name)),
		}
		lastModified := file.LastModified
		entry.LastModified = &lastModified
		if public, err := s.services.Storage.BuildPublicURL(file.Key); err == nil && public != "" {
			entry.PublicURL = &public
		}
		if signed, err := s.services.Storage.CreateDownloadURL(ctx, file.Key); err == nil && signed != "" {
			entry.SignedURL = &signed
		}
		entries = append(entries, entry)
	}
	return entries, nil
}

// CreateUploadURL presigns a PUT for a new asset.
func (s *AssetService) CreateUploadURL(ctx context.Context, request AssetUploadCommand) (*objectstorage.UploadResult, error) {
	fileName, err := cleanFileName(request.FileName)
	if err != nil {
		return nil, err
	}
	if request.FileSize == nil || *request.FileSize <= 0 {
		return nil, domain.Biz("File size must be greater than 0")
	}
	if *request.FileSize > s.services.Storage.MaxFileSizeBytes() {
		return nil, domain.Biz("File size exceeds the configured limit")
	}
	prefix, err := s.dirPrefix(request.Path)
	if err != nil {
		return nil, err
	}
	contentType := guessContentType(fileName)
	if contentType == "" {
		contentType = request.ContentType
	}
	if strings.TrimSpace(contentType) == "" {
		contentType = "application/octet-stream"
	}
	return s.services.Storage.PresignPut(ctx, prefix+fileName, contentType)
}

// Delete removes one asset, or every asset under a folder key.
func (s *AssetService) Delete(ctx context.Context, key string) error {
	if strings.TrimSpace(key) == "" || !strings.HasPrefix(key, s.assetPrefix()+"/") || strings.Contains(key, "..") {
		return domain.Biz("Invalid asset key")
	}
	if !strings.HasSuffix(key, "/") {
		return s.services.Storage.DeleteObject(ctx, key)
	}
	objects, err := s.services.Storage.ListObjects(ctx, key)
	if err != nil {
		return err
	}
	if len(objects) == 0 {
		// An empty folder exists only as its own marker object.
		return s.services.Storage.DeleteObject(ctx, key)
	}
	keys := make([]string, 0, len(objects))
	for _, object := range objects {
		keys = append(keys, object.Key)
	}
	return s.services.Storage.DeleteObjects(ctx, keys)
}

func cleanFileName(fileName string) (string, error) {
	name := strings.TrimLeft(strings.TrimSpace(fileName), "/")
	if name == "" || strings.HasSuffix(name, "/") || strings.Contains(name, "..") {
		return "", domain.Biz("Invalid file name")
	}
	return name, nil
}

func lastSegment(key string) string {
	stripped := strings.TrimSuffix(key, "/")
	if index := strings.LastIndex(stripped, "/"); index >= 0 {
		return stripped[index+1:]
	}
	return stripped
}

// guessContentType maps an extension to a MIME type; "" when unknown.
func guessContentType(name string) string {
	contentType := mime.TypeByExtension(filepath.Ext(name))
	if index := strings.Index(contentType, ";"); index >= 0 {
		contentType = strings.TrimSpace(contentType[:index])
	}
	return contentType
}
