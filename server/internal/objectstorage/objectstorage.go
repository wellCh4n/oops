// Package objectstorage is the S3-compatible store for ZIP build sources and
// static assets, the Go counterpart of infrastructure/objectstorage.
package objectstorage

import (
	"context"
	"fmt"
	"mime"
	"net/url"
	"path"
	"regexp"
	"strings"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

// Config mirrors ObjectStorageProperties.
type Config struct {
	Enabled                      bool
	Endpoint                     string
	Region                       string
	Bucket                       string
	AccessKey                    string
	SecretKey                    string
	PathStyleAccess              bool
	KeyPrefix                    string
	AssetKeyPrefix               string
	AssetBaseURL                 string
	UploadURLExpirationSeconds   int64
	DownloadURLExpirationSeconds int64
	MaxFileSizeBytes             int64
}

func (config *Config) applyDefaults() {
	if config.Region == "" {
		config.Region = "cn-hangzhou"
	}
	if config.KeyPrefix == "" {
		config.KeyPrefix = "oops-package"
	}
	if config.AssetKeyPrefix == "" {
		config.AssetKeyPrefix = "oops-assets"
	}
	if config.UploadURLExpirationSeconds == 0 {
		config.UploadURLExpirationSeconds = 900
	}
	if config.DownloadURLExpirationSeconds == 0 {
		config.DownloadURLExpirationSeconds = 1800
	}
	if config.MaxFileSizeBytes == 0 {
		config.MaxFileSizeBytes = 524288000
	}
}

type bizError struct{ message string }

func (e *bizError) Error() string { return e.message }

// IsBizError reports a user-facing storage failure.
func IsBizError(err error) bool {
	_, matches := err.(*bizError)
	return matches
}

func bizf(format string, args ...any) error {
	return &bizError{message: fmt.Sprintf(format, args...)}
}

type Storage struct {
	config Config
	client *minio.Client
}

func New(config Config) (*Storage, error) {
	config.applyDefaults()
	storage := &Storage{config: config}
	if !config.Enabled {
		return storage, nil
	}
	endpoint := config.Endpoint
	secure := strings.HasPrefix(endpoint, "https://")
	endpoint = strings.TrimPrefix(strings.TrimPrefix(endpoint, "https://"), "http://")
	client, err := minio.New(endpoint, &minio.Options{
		Creds:        credentials.NewStaticV4(config.AccessKey, config.SecretKey, ""),
		Secure:       secure,
		Region:       config.Region,
		BucketLookup: bucketLookup(config.PathStyleAccess),
	})
	if err != nil {
		return nil, err
	}
	storage.client = client
	return storage, nil
}

func bucketLookup(pathStyle bool) minio.BucketLookupType {
	if pathStyle {
		return minio.BucketLookupPath
	}
	return minio.BucketLookupAuto
}

func (storage *Storage) ensureEnabled() error {
	if !storage.config.Enabled {
		return bizf("Build source storage is not configured")
	}
	if storage.config.Bucket == "" || storage.config.AccessKey == "" || storage.config.SecretKey == "" {
		return bizf("Build source storage credentials are incomplete")
	}
	return nil
}

// UploadResult mirrors ObjectStorageUploadResult.
type UploadResult struct {
	ObjectKey string            `json:"objectKey"`
	ObjectURL string            `json:"objectUrl"`
	UploadURL string            `json:"uploadUrl"`
	Headers   map[string]string `json:"headers"`
}

var unsafeFileName = regexp.MustCompile(`[^a-zA-Z0-9._-]`)

// CreateBuildSourceUpload mirrors createUpload: a presigned PUT for a ZIP
// build source under the package prefix.
func (storage *Storage) CreateBuildSourceUpload(ctx context.Context, namespace, applicationName, fileName, contentType string, fileSize int64) (*UploadResult, error) {
	if err := storage.ensureEnabled(); err != nil {
		return nil, err
	}
	if strings.TrimSpace(fileName) == "" {
		return nil, bizf("File name is required")
	}
	if !strings.HasSuffix(strings.ToLower(fileName), ".zip") {
		return nil, bizf("Only zip files are supported")
	}
	if fileSize <= 0 {
		return nil, bizf("File size must be greater than 0")
	}
	if fileSize > storage.config.MaxFileSizeBytes {
		return nil, bizf("File size exceeds the configured limit")
	}
	prefix := strings.Trim(storage.config.KeyPrefix, "/")
	objectKey := fmt.Sprintf("%s/%s/%s/%d-%s", prefix, namespace, applicationName,
		time.Now().UnixMilli(), unsafeFileName.ReplaceAllString(fileName, "-"))
	if contentType == "" {
		contentType = "application/zip"
	}
	return storage.presignPut(ctx, objectKey, contentType)
}

// PresignPut mirrors presignPut for arbitrary keys (asset uploads).
func (storage *Storage) PresignPut(ctx context.Context, objectKey, contentType string) (*UploadResult, error) {
	if err := storage.ensureEnabled(); err != nil {
		return nil, err
	}
	if objectKey == "" {
		return nil, bizf("Object key is required")
	}
	if contentType == "" {
		contentType = "application/octet-stream"
	}
	return storage.presignPut(ctx, objectKey, contentType)
}

func (storage *Storage) presignPut(ctx context.Context, objectKey, contentType string) (*UploadResult, error) {
	presigned, err := storage.client.PresignedPutObject(ctx, storage.config.Bucket, objectKey,
		time.Duration(storage.config.UploadURLExpirationSeconds)*time.Second)
	if err != nil {
		return nil, err
	}
	objectURL := *presigned
	objectURL.RawQuery = ""
	return &UploadResult{
		ObjectKey: objectKey,
		ObjectURL: objectURL.String(),
		UploadURL: presigned.String(),
		Headers:   map[string]string{"Content-Type": contentType},
	}, nil
}

// CreateDownloadURL mirrors createDownloadUrl.
func (storage *Storage) CreateDownloadURL(ctx context.Context, objectKey string) (string, error) {
	if err := storage.ensureEnabled(); err != nil {
		return "", err
	}
	if objectKey == "" {
		return "", bizf("Build source object key is required")
	}
	presigned, err := storage.client.PresignedGetObject(ctx, storage.config.Bucket, objectKey,
		time.Duration(storage.config.DownloadURLExpirationSeconds)*time.Second, url.Values{})
	if err != nil {
		return "", err
	}
	return presigned.String(), nil
}

// ResolveDownloadURL mirrors resolveDownloadUrl: direct URLs pass through.
func (storage *Storage) ResolveDownloadURL(ctx context.Context, repository string) (string, error) {
	if repository == "" {
		return "", bizf("Build source repository is required")
	}
	if strings.HasPrefix(repository, "http://") || strings.HasPrefix(repository, "https://") {
		return repository, nil
	}
	return storage.CreateDownloadURL(ctx, repository)
}

func joinURL(base, suffix string) string {
	return strings.TrimRight(base, "/") + "/" + strings.TrimLeft(suffix, "/")
}

// BuildPublicURL mirrors buildPublicUrl.
func (storage *Storage) BuildPublicURL(objectKey string) (string, error) {
	if objectKey == "" {
		return "", bizf("Object key is required")
	}
	base := storage.config.AssetBaseURL
	if base == "" {
		base = joinURL(storage.config.Endpoint, storage.config.Bucket)
	}
	return joinURL(base, objectKey), nil
}

// AssetEntry mirrors the Java record.
type AssetEntry struct {
	Type         string  `json:"type"`
	Name         string  `json:"name"`
	Key          string  `json:"key"`
	Size         int64   `json:"size"`
	LastModified *string `json:"lastModified"`
	ContentType  *string `json:"contentType"`
	PublicURL    *string `json:"publicUrl"`
	SignedURL    *string `json:"signedUrl"`
}

func (storage *Storage) assetPrefix() string {
	return strings.Trim(storage.config.AssetKeyPrefix, "/")
}

func (storage *Storage) dirPrefix(rawPath string) (string, error) {
	normalized := strings.Trim(strings.TrimSpace(rawPath), "/")
	if strings.Contains(normalized, "..") {
		return "", bizf("Invalid path")
	}
	if normalized == "" {
		return storage.assetPrefix() + "/", nil
	}
	return storage.assetPrefix() + "/" + normalized + "/", nil
}

// ListAssets mirrors StaticAssetService.list.
func (storage *Storage) ListAssets(ctx context.Context, rawPath string) ([]AssetEntry, error) {
	if err := storage.ensureEnabled(); err != nil {
		return nil, err
	}
	prefix, err := storage.dirPrefix(rawPath)
	if err != nil {
		return nil, err
	}
	folders, files := []AssetEntry{}, []AssetEntry{}
	for object := range storage.client.ListObjects(ctx, storage.config.Bucket, minio.ListObjectsOptions{
		Prefix: prefix,
	}) {
		if object.Err != nil {
			return nil, object.Err
		}
		relative := strings.TrimPrefix(object.Key, prefix)
		if slash := strings.Index(relative, "/"); slash >= 0 {
			folderKey := prefix + relative[:slash+1]
			duplicate := false
			for _, folder := range folders {
				if folder.Key == folderKey {
					duplicate = true
				}
			}
			if !duplicate {
				folders = append(folders, AssetEntry{
					Type: "FOLDER", Name: strings.TrimSuffix(relative[:slash+1], "/"), Key: folderKey,
				})
			}
			continue
		}
		if object.Key == prefix || strings.HasSuffix(object.Key, "/") {
			continue
		}
		name := path.Base(object.Key)
		contentType := mime.TypeByExtension(path.Ext(name))
		var contentTypePointer *string
		if contentType != "" {
			contentTypePointer = &contentType
		}
		lastModified := object.LastModified.UTC().Format(time.RFC3339)
		publicURL, _ := storage.BuildPublicURL(object.Key)
		signedURL, _ := storage.CreateDownloadURL(ctx, object.Key)
		files = append(files, AssetEntry{
			Type: "FILE", Name: name, Key: object.Key, Size: object.Size,
			LastModified: &lastModified, ContentType: contentTypePointer,
			PublicURL: &publicURL, SignedURL: &signedURL,
		})
	}
	return append(folders, files...), nil
}

// CreateAssetUploadURL mirrors createUploadUrl.
func (storage *Storage) CreateAssetUploadURL(ctx context.Context, rawPath, fileName, contentType string, fileSize int64) (*UploadResult, error) {
	if err := storage.ensureEnabled(); err != nil {
		return nil, err
	}
	if strings.TrimSpace(fileName) == "" {
		return nil, bizf("File name is required")
	}
	if fileSize <= 0 {
		return nil, bizf("File size must be greater than 0")
	}
	if fileSize > storage.config.MaxFileSizeBytes {
		return nil, bizf("File size exceeds the configured limit")
	}
	cleaned := strings.TrimLeft(strings.TrimSpace(fileName), "/")
	if cleaned == "" || strings.HasSuffix(cleaned, "/") || strings.Contains(cleaned, "..") {
		return nil, bizf("Invalid file name")
	}
	prefix, err := storage.dirPrefix(rawPath)
	if err != nil {
		return nil, err
	}
	// Prefer the extension-based guess, like resolveUploadContentType.
	if guessed := mime.TypeByExtension(path.Ext(cleaned)); guessed != "" {
		contentType = guessed
	} else if contentType == "" {
		contentType = "application/octet-stream"
	}
	return storage.presignPut(ctx, prefix+cleaned, contentType)
}

// DeleteAsset mirrors StaticAssetService.delete: keys under the asset prefix
// only; a folder key removes everything beneath it.
func (storage *Storage) DeleteAsset(ctx context.Context, key string) error {
	if err := storage.ensureEnabled(); err != nil {
		return err
	}
	if key == "" || !strings.HasPrefix(key, storage.assetPrefix()+"/") || strings.Contains(key, "..") {
		return bizf("Invalid asset key")
	}
	if strings.HasSuffix(key, "/") {
		for object := range storage.client.ListObjects(ctx, storage.config.Bucket, minio.ListObjectsOptions{
			Prefix: key, Recursive: true,
		}) {
			if object.Err != nil {
				return object.Err
			}
			if err := storage.client.RemoveObject(ctx, storage.config.Bucket, object.Key, minio.RemoveObjectOptions{}); err != nil {
				return err
			}
		}
		return nil
	}
	return storage.client.RemoveObject(ctx, storage.config.Bucket, key, minio.RemoveObjectOptions{})
}
