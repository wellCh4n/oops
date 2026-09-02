// Package objectstorage is the S3-compatible store behind ZIP build-source
// uploads and the static asset browser. It ports the Java ObjectStorageService:
// same validation messages, same object-key layout, same presigned-URL shaping.
package objectstorage

import (
	"context"
	"fmt"
	"net/url"
	"regexp"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/aws-sdk-go-v2/service/s3/types"

	"github.com/wellch4n/oops/server/internal/domain"
)

// Options mirrors the oops.object-storage.* properties.
type Options struct {
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

// Defaults matching ObjectStorageProperties.
const (
	DefaultRegion                       = "cn-hangzhou"
	DefaultKeyPrefix                    = "oops-package"
	DefaultAssetKeyPrefix               = "oops-assets"
	DefaultUploadURLExpirationSeconds   = 900
	DefaultDownloadURLExpirationSeconds = 1800
	DefaultMaxFileSizeBytes             = 524288000
	// MaxListedObjects caps ListObjects, like MAX_LISTED_OBJECTS in Java.
	MaxListedObjects = 5000
	deleteBatchSize  = 1000
)

// UploadResult is what the client needs to PUT a file and later refer to it.
type UploadResult struct {
	ObjectKey string            `json:"objectKey"`
	ObjectURL string            `json:"objectUrl"`
	UploadURL string            `json:"uploadUrl"`
	Headers   map[string]string `json:"headers"`
}

// ObjectInfo is one listed object.
type ObjectInfo struct {
	Key          string
	Size         int64
	LastModified time.Time
}

// Service talks to the configured bucket. When storage is disabled the
// service still exists, and every operation reports the "not configured"
// business error so callers need no nil checks.
type Service struct {
	options Options
	client  *s3.Client
	presign *s3.PresignClient
}

// New applies defaults and, when enabled, builds the S3 client with static
// credentials, the endpoint override and the path-style flag.
func New(options Options) (*Service, error) {
	options = withDefaults(options)
	service := &Service{options: options}
	if !options.Enabled {
		return service, nil
	}
	client := s3.New(s3.Options{
		Region:       options.Region,
		Credentials:  credentials.NewStaticCredentialsProvider(options.AccessKey, options.SecretKey, ""),
		UsePathStyle: options.PathStyleAccess,
		// Non-AWS stores (RustFS, MinIO, OSS) do not all understand the newer
		// flexible checksums, and the Java SDK v2 the backend used never sent them.
		RequestChecksumCalculation: aws.RequestChecksumCalculationWhenRequired,
		ResponseChecksumValidation: aws.ResponseChecksumValidationWhenRequired,
	}, func(s3Options *s3.Options) {
		if strings.TrimSpace(options.Endpoint) != "" {
			s3Options.BaseEndpoint = aws.String(strings.TrimSpace(options.Endpoint))
		}
	})
	service.client = client
	service.presign = s3.NewPresignClient(client)
	return service, nil
}

func withDefaults(options Options) Options {
	if strings.TrimSpace(options.Region) == "" {
		options.Region = DefaultRegion
	}
	if strings.TrimSpace(options.KeyPrefix) == "" {
		options.KeyPrefix = DefaultKeyPrefix
	}
	if strings.TrimSpace(options.AssetKeyPrefix) == "" {
		options.AssetKeyPrefix = DefaultAssetKeyPrefix
	}
	if options.UploadURLExpirationSeconds <= 0 {
		options.UploadURLExpirationSeconds = DefaultUploadURLExpirationSeconds
	}
	if options.DownloadURLExpirationSeconds <= 0 {
		options.DownloadURLExpirationSeconds = DefaultDownloadURLExpirationSeconds
	}
	if options.MaxFileSizeBytes <= 0 {
		options.MaxFileSizeBytes = DefaultMaxFileSizeBytes
	}
	return options
}

// Enabled reports whether object storage is switched on.
func (service *Service) Enabled() bool { return service.options.Enabled }

// Options returns the effective (defaulted) options.
func (service *Service) Options() Options { return service.options }

// MaxFileSizeBytes is the configured upload ceiling.
func (service *Service) MaxFileSizeBytes() int64 { return service.options.MaxFileSizeBytes }

// AssetKeyPrefix returns the asset prefix with surrounding slashes stripped.
func (service *Service) AssetKeyPrefix() string {
	return strings.Trim(service.options.AssetKeyPrefix, "/")
}

func (service *Service) ensureEnabled() error {
	if !service.options.Enabled {
		return domain.Biz("Build source storage is not configured")
	}
	if isBlank(service.options.Bucket) || isBlank(service.options.AccessKey) || isBlank(service.options.SecretKey) {
		return domain.Biz("Build source storage credentials are incomplete")
	}
	return nil
}

func (service *Service) presigner() (*s3.PresignClient, error) {
	if service.presign == nil {
		return nil, domain.Biz("Build source storage is not properly initialized")
	}
	return service.presign, nil
}

func (service *Service) s3Client() (*s3.Client, error) {
	if service.client == nil {
		return nil, domain.Biz("Object storage is not properly initialized")
	}
	return service.client, nil
}

var unsafeFileNameCharacters = regexp.MustCompile(`[^a-zA-Z0-9._-]`)

// CreateUpload validates a ZIP build-source upload request and presigns its PUT.
func (service *Service) CreateUpload(ctx context.Context, namespace, applicationName, fileName string, fileSize *int64, contentType string) (*UploadResult, error) {
	if err := service.ensureEnabled(); err != nil {
		return nil, err
	}
	if isBlank(fileName) {
		return nil, domain.Biz("File name is required")
	}
	if !strings.HasSuffix(strings.ToLower(fileName), ".zip") {
		return nil, domain.Biz("Only zip files are supported")
	}
	if fileSize == nil || *fileSize <= 0 {
		return nil, domain.Biz("File size must be greater than 0")
	}
	if *fileSize > service.options.MaxFileSizeBytes {
		return nil, domain.Biz("File size exceeds the configured limit")
	}
	objectKey := BuildSourceObjectKey(service.options.KeyPrefix, namespace, applicationName, fileName, time.Now())
	if isBlank(contentType) {
		contentType = "application/zip"
	}
	return service.presignPutObject(ctx, objectKey, contentType)
}

// BuildSourceObjectKey renders "<prefix>/<namespace>/<app>/<millis>-<sanitized file name>".
func BuildSourceObjectKey(keyPrefix, namespace, applicationName, fileName string, at time.Time) string {
	prefix := strings.Trim(keyPrefix, "/")
	if prefix == "" {
		prefix = DefaultKeyPrefix
	}
	sanitized := unsafeFileNameCharacters.ReplaceAllString(fileName, "-")
	return fmt.Sprintf("%s/%s/%s/%d-%s", prefix, namespace, applicationName, at.UnixMilli(), sanitized)
}

// PresignPut presigns a PUT for an arbitrary key (static assets).
func (service *Service) PresignPut(ctx context.Context, objectKey, contentType string) (*UploadResult, error) {
	if err := service.ensureEnabled(); err != nil {
		return nil, err
	}
	if isBlank(objectKey) {
		return nil, domain.Biz("Object key is required")
	}
	if isBlank(contentType) {
		contentType = "application/octet-stream"
	}
	return service.presignPutObject(ctx, objectKey, contentType)
}

func (service *Service) presignPutObject(ctx context.Context, objectKey, contentType string) (*UploadResult, error) {
	presigner, err := service.presigner()
	if err != nil {
		return nil, err
	}
	request, err := presigner.PresignPutObject(ctx, &s3.PutObjectInput{
		Bucket:      aws.String(service.options.Bucket),
		Key:         aws.String(objectKey),
		ContentType: aws.String(contentType),
	}, s3.WithPresignExpires(time.Duration(service.options.UploadURLExpirationSeconds)*time.Second))
	if err != nil {
		return nil, fmt.Errorf("presign put %s: %w", objectKey, err)
	}
	objectURL, err := ToObjectURL(request.URL)
	if err != nil {
		return nil, err
	}
	return &UploadResult{
		ObjectKey: objectKey,
		ObjectURL: objectURL,
		UploadURL: request.URL,
		Headers:   map[string]string{"Content-Type": contentType},
	}, nil
}

// ToObjectURL strips query and fragment from a presigned URL, keeping scheme,
// user info, host, port and path.
func ToObjectURL(presigned string) (string, error) {
	parsed, err := url.Parse(presigned)
	if err != nil || parsed.Scheme == "" || parsed.Host == "" {
		return "", domain.Biz("Failed to build object url")
	}
	parsed.RawQuery = ""
	parsed.ForceQuery = false
	parsed.Fragment = ""
	parsed.RawFragment = ""
	return parsed.String(), nil
}

// CreateDownloadURL presigns a GET for a build-source object.
func (service *Service) CreateDownloadURL(ctx context.Context, objectKey string) (string, error) {
	if err := service.ensureEnabled(); err != nil {
		return "", err
	}
	if isBlank(objectKey) {
		return "", domain.Biz("Build source object key is required")
	}
	presigner, err := service.presigner()
	if err != nil {
		return "", err
	}
	request, err := presigner.PresignGetObject(ctx, &s3.GetObjectInput{
		Bucket: aws.String(service.options.Bucket),
		Key:    aws.String(objectKey),
	}, s3.WithPresignExpires(time.Duration(service.options.DownloadURLExpirationSeconds)*time.Second))
	if err != nil {
		return "", fmt.Errorf("presign get %s: %w", objectKey, err)
	}
	return request.URL, nil
}

// ResolveDownloadURL returns an http(s) repository verbatim and presigns
// anything else as an object key.
func (service *Service) ResolveDownloadURL(ctx context.Context, repository string) (string, error) {
	if isBlank(repository) {
		return "", domain.Biz("Build source repository is required")
	}
	trimmed := strings.TrimSpace(repository)
	if strings.HasPrefix(trimmed, "http://") || strings.HasPrefix(trimmed, "https://") {
		return trimmed, nil
	}
	return service.CreateDownloadURL(ctx, trimmed)
}

// ListDirectory lists one level under prefix: common prefixes as folders,
// direct children as files.
func (service *Service) ListDirectory(ctx context.Context, prefix string) (folders []string, files []ObjectInfo, err error) {
	if err := service.ensureEnabled(); err != nil {
		return nil, nil, err
	}
	client, err := service.s3Client()
	if err != nil {
		return nil, nil, err
	}
	paginator := s3.NewListObjectsV2Paginator(client, &s3.ListObjectsV2Input{
		Bucket:    aws.String(service.options.Bucket),
		Prefix:    aws.String(prefix),
		Delimiter: aws.String("/"),
	})
	folders = []string{}
	files = []ObjectInfo{}
	for paginator.HasMorePages() {
		page, err := paginator.NextPage(ctx)
		if err != nil {
			return nil, nil, fmt.Errorf("list directory %q: %w", prefix, err)
		}
		for _, commonPrefix := range page.CommonPrefixes {
			folders = append(folders, aws.ToString(commonPrefix.Prefix))
		}
		for _, object := range page.Contents {
			files = append(files, toObjectInfo(object))
		}
	}
	return folders, files, nil
}

// ListObjects lists every object under prefix, stopping at MaxListedObjects.
func (service *Service) ListObjects(ctx context.Context, prefix string) ([]ObjectInfo, error) {
	if err := service.ensureEnabled(); err != nil {
		return nil, err
	}
	client, err := service.s3Client()
	if err != nil {
		return nil, err
	}
	paginator := s3.NewListObjectsV2Paginator(client, &s3.ListObjectsV2Input{
		Bucket: aws.String(service.options.Bucket),
		Prefix: aws.String(prefix),
	})
	objects := []ObjectInfo{}
	for paginator.HasMorePages() {
		page, err := paginator.NextPage(ctx)
		if err != nil {
			return nil, fmt.Errorf("list objects %q: %w", prefix, err)
		}
		for _, object := range page.Contents {
			objects = append(objects, toObjectInfo(object))
			if len(objects) >= MaxListedObjects {
				return objects, nil
			}
		}
	}
	return objects, nil
}

func toObjectInfo(object types.Object) ObjectInfo {
	return ObjectInfo{
		Key:          aws.ToString(object.Key),
		Size:         aws.ToInt64(object.Size),
		LastModified: aws.ToTime(object.LastModified),
	}
}

// DeleteObject removes one object.
func (service *Service) DeleteObject(ctx context.Context, objectKey string) error {
	if err := service.ensureEnabled(); err != nil {
		return err
	}
	if isBlank(objectKey) {
		return domain.Biz("Object key is required")
	}
	client, err := service.s3Client()
	if err != nil {
		return err
	}
	_, err = client.DeleteObject(ctx, &s3.DeleteObjectInput{
		Bucket: aws.String(service.options.Bucket),
		Key:    aws.String(objectKey),
	})
	if err != nil {
		return fmt.Errorf("delete object %s: %w", objectKey, err)
	}
	return nil
}

// DeleteObjects removes many objects in batches of 1000; an empty list is a no-op.
func (service *Service) DeleteObjects(ctx context.Context, objectKeys []string) error {
	if len(objectKeys) == 0 {
		return nil
	}
	if err := service.ensureEnabled(); err != nil {
		return err
	}
	client, err := service.s3Client()
	if err != nil {
		return err
	}
	for start := 0; start < len(objectKeys); start += deleteBatchSize {
		end := min(start+deleteBatchSize, len(objectKeys))
		identifiers := make([]types.ObjectIdentifier, 0, end-start)
		for _, key := range objectKeys[start:end] {
			identifiers = append(identifiers, types.ObjectIdentifier{Key: aws.String(key)})
		}
		_, err := client.DeleteObjects(ctx, &s3.DeleteObjectsInput{
			Bucket: aws.String(service.options.Bucket),
			Delete: &types.Delete{Objects: identifiers, Quiet: aws.Bool(true)},
		})
		if err != nil {
			return fmt.Errorf("delete %d objects: %w", end-start, err)
		}
	}
	return nil
}

// BuildPublicURL joins the asset base URL (or endpoint/bucket) with the key.
func (service *Service) BuildPublicURL(objectKey string) (string, error) {
	if isBlank(objectKey) {
		return "", domain.Biz("Object key is required")
	}
	base := service.options.AssetBaseURL
	if isBlank(base) {
		base = JoinURL(service.options.Endpoint, service.options.Bucket)
	}
	return JoinURL(base, objectKey), nil
}

// JoinURL joins base and suffix with exactly one slash.
func JoinURL(base, suffix string) string {
	return strings.TrimRight(strings.TrimSpace(base), "/") + "/" + strings.TrimLeft(strings.TrimSpace(suffix), "/")
}

func isBlank(value string) bool { return strings.TrimSpace(value) == "" }
