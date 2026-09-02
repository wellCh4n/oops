package objectstorage

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/wellch4n/oops/server/internal/domain"
)

func enabledService(t *testing.T) *Service {
	t.Helper()
	service, err := New(Options{
		Enabled:         true,
		Endpoint:        "http://127.0.0.1:19000",
		Bucket:          "oops-build-sources",
		AccessKey:       "integration",
		SecretKey:       "integration-secret",
		PathStyleAccess: true,
		KeyPrefix:       "/build-sources/",
	})
	if err != nil {
		t.Fatal(err)
	}
	return service
}

func bizMessage(t *testing.T, err error) string {
	t.Helper()
	if err == nil || !domain.IsBiz(err) {
		t.Fatalf("expected biz error, got %v", err)
	}
	return domain.BizMessage(err)
}

func TestDisabledServiceReportsNotConfigured(t *testing.T) {
	service, err := New(Options{})
	if err != nil {
		t.Fatal(err)
	}
	if service.Enabled() {
		t.Fatal("should be disabled")
	}
	ctx := context.Background()
	size := int64(10)
	_, err = service.CreateUpload(ctx, "ns", "app", "a.zip", &size, "")
	if got := bizMessage(t, err); got != "Build source storage is not configured" {
		t.Fatal(got)
	}
	if _, err := service.CreateDownloadURL(ctx, "k"); bizMessage(t, err) != "Build source storage is not configured" {
		t.Fatal(err)
	}
	if _, _, err := service.ListDirectory(ctx, "p/"); bizMessage(t, err) != "Build source storage is not configured" {
		t.Fatal(err)
	}
	if err := service.DeleteObjects(ctx, nil); err != nil {
		t.Fatalf("empty delete is a no-op even when disabled: %v", err)
	}
	if service.Options().KeyPrefix != DefaultKeyPrefix || service.Options().MaxFileSizeBytes != DefaultMaxFileSizeBytes {
		t.Fatalf("defaults not applied: %+v", service.Options())
	}
}

func TestIncompleteCredentials(t *testing.T) {
	service, _ := New(Options{Enabled: true, Bucket: "b", AccessKey: "a"})
	_, err := service.PresignPut(context.Background(), "k", "")
	if got := bizMessage(t, err); got != "Build source storage credentials are incomplete" {
		t.Fatal(got)
	}
}

func TestCreateUploadValidation(t *testing.T) {
	service := enabledService(t)
	ctx := context.Background()
	zero, negative, huge, fine := int64(0), int64(-1), service.MaxFileSizeBytes()+1, int64(100)
	cases := []struct {
		fileName string
		size     *int64
		want     string
	}{
		{"", &fine, "File name is required"},
		{"  ", &fine, "File name is required"},
		{"a.tar.gz", &fine, "Only zip files are supported"},
		{"a.zip", nil, "File size must be greater than 0"},
		{"a.zip", &zero, "File size must be greater than 0"},
		{"a.zip", &negative, "File size must be greater than 0"},
		{"a.zip", &huge, "File size exceeds the configured limit"},
	}
	for _, testCase := range cases {
		_, err := service.CreateUpload(ctx, "ns", "app", testCase.fileName, testCase.size, "")
		if got := bizMessage(t, err); got != testCase.want {
			t.Fatalf("%q/%v: got %q want %q", testCase.fileName, testCase.size, got, testCase.want)
		}
	}
}

func TestCreateUploadShapesKeyAndURLs(t *testing.T) {
	service := enabledService(t)
	size := int64(100)
	before := time.Now().UnixMilli()
	result, err := service.CreateUpload(context.Background(), "team", "shop", "My Source (v2).ZIP", &size, "")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(result.ObjectKey, "build-sources/team/shop/") || !strings.HasSuffix(result.ObjectKey, "-My-Source--v2-.ZIP") {
		t.Fatalf("object key %q", result.ObjectKey)
	}
	millisPart := strings.TrimSuffix(strings.TrimPrefix(result.ObjectKey, "build-sources/team/shop/"), "-My-Source--v2-.ZIP")
	if len(millisPart) < 13 || millisPart < "1" {
		t.Fatalf("millis part %q", millisPart)
	}
	if result.Headers["Content-Type"] != "application/zip" {
		t.Fatalf("headers %v", result.Headers)
	}
	if !strings.Contains(result.UploadURL, "X-Amz-Signature=") || !strings.Contains(result.UploadURL, "X-Amz-Expires=900") {
		t.Fatalf("upload url %q", result.UploadURL)
	}
	wantObjectURL := "http://127.0.0.1:19000/oops-build-sources/" + result.ObjectKey
	if result.ObjectURL != wantObjectURL {
		t.Fatalf("object url %q want %q", result.ObjectURL, wantObjectURL)
	}
	if !strings.HasPrefix(result.UploadURL, wantObjectURL+"?") {
		t.Fatalf("upload url should start with the object url: %q", result.UploadURL)
	}
	_ = before
}

func TestBuildSourceObjectKey(t *testing.T) {
	at := time.UnixMilli(1700000000000)
	got := BuildSourceObjectKey("", "ns", "app", "hello world!.zip", at)
	if got != "oops-package/ns/app/1700000000000-hello-world-.zip" {
		t.Fatal(got)
	}
}

func TestPresignPutDefaultsContentType(t *testing.T) {
	service := enabledService(t)
	result, err := service.PresignPut(context.Background(), "oops-assets/logo.png", "")
	if err != nil {
		t.Fatal(err)
	}
	if result.Headers["Content-Type"] != "application/octet-stream" {
		t.Fatalf("headers %v", result.Headers)
	}
	if _, err := service.PresignPut(context.Background(), " ", "image/png"); bizMessage(t, err) != "Object key is required" {
		t.Fatal(err)
	}
}

func TestResolveDownloadURL(t *testing.T) {
	service := enabledService(t)
	ctx := context.Background()
	if _, err := service.ResolveDownloadURL(ctx, ""); bizMessage(t, err) != "Build source repository is required" {
		t.Fatal(err)
	}
	direct, err := service.ResolveDownloadURL(ctx, "https://example.com/src.zip")
	if err != nil || direct != "https://example.com/src.zip" {
		t.Fatalf("http passthrough: %q %v", direct, err)
	}
	signed, err := service.ResolveDownloadURL(ctx, "build-sources/ns/app/1-a.zip")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(signed, "http://127.0.0.1:19000/oops-build-sources/build-sources/ns/app/1-a.zip?") || !strings.Contains(signed, "X-Amz-Expires=1800") {
		t.Fatalf("signed %q", signed)
	}
	if _, err := service.CreateDownloadURL(ctx, ""); bizMessage(t, err) != "Build source object key is required" {
		t.Fatal(err)
	}
}

func TestBuildPublicURL(t *testing.T) {
	service := enabledService(t)
	got, err := service.BuildPublicURL("/oops-assets/a.png")
	if err != nil || got != "http://127.0.0.1:19000/oops-build-sources/oops-assets/a.png" {
		t.Fatalf("%q %v", got, err)
	}
	withBase, _ := New(Options{Enabled: true, AssetBaseURL: "https://cdn.example.com/static/"})
	got, _ = withBase.BuildPublicURL("oops-assets/a.png")
	if got != "https://cdn.example.com/static/oops-assets/a.png" {
		t.Fatal(got)
	}
	if _, err := service.BuildPublicURL(""); bizMessage(t, err) != "Object key is required" {
		t.Fatal(err)
	}
}

func TestToObjectURL(t *testing.T) {
	got, err := ToObjectURL("https://user@host:8443/bucket/key%20x?X-Amz=1#frag")
	if err != nil || got != "https://user@host:8443/bucket/key%20x" {
		t.Fatalf("%q %v", got, err)
	}
	if _, err := ToObjectURL("::not a url"); bizMessage(t, err) != "Failed to build object url" {
		t.Fatal(err)
	}
}
