package objectstorage

import (
	"bytes"
	"context"
	"net/http"
	"os"
	"testing"
	"time"
)

// TestRustFSRoundTrip runs against the integration stack's RustFS when
// OOPS_S3_TEST_ENDPOINT is set (e.g. http://127.0.0.1:19000 from
// tests/integration/docker-compose.yml). Credentials default to the stack's.
func TestRustFSRoundTrip(t *testing.T) {
	endpoint := os.Getenv("OOPS_S3_TEST_ENDPOINT")
	if endpoint == "" {
		t.Skip("OOPS_S3_TEST_ENDPOINT not set")
	}
	envOr := func(key, fallback string) string {
		if value := os.Getenv(key); value != "" {
			return value
		}
		return fallback
	}
	service, err := New(Options{
		Enabled:         true,
		Endpoint:        endpoint,
		Region:          "us-east-1",
		Bucket:          envOr("OOPS_S3_TEST_BUCKET", "oops-build-sources"),
		AccessKey:       envOr("OOPS_S3_TEST_ACCESS_KEY", "integration"),
		SecretKey:       envOr("OOPS_S3_TEST_SECRET_KEY", "integration-secret"),
		PathStyleAccess: true,
		KeyPrefix:       "go-integration",
	})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	payload := []byte("PK\x03\x04 not really a zip but fine for storage")
	size := int64(len(payload))
	upload, err := service.CreateUpload(ctx, "itest", "app", "source.zip", &size, "")
	if err != nil {
		t.Fatal(err)
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPut, upload.UploadURL, bytes.NewReader(payload))
	if err != nil {
		t.Fatal(err)
	}
	for name, value := range upload.Headers {
		request.Header.Set(name, value)
	}
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode/100 != 2 {
		t.Fatalf("PUT returned %s", response.Status)
	}

	objects, err := service.ListObjects(ctx, "go-integration/itest/app/")
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, object := range objects {
		if object.Key == upload.ObjectKey && object.Size == size {
			found = true
		}
	}
	if !found {
		t.Fatalf("uploaded key %s not listed in %+v", upload.ObjectKey, objects)
	}
	folders, _, err := service.ListDirectory(ctx, "go-integration/")
	if err != nil {
		t.Fatal(err)
	}
	if len(folders) == 0 || folders[0] != "go-integration/itest/" {
		t.Fatalf("folders %v", folders)
	}

	downloadURL, err := service.CreateDownloadURL(ctx, upload.ObjectKey)
	if err != nil {
		t.Fatal(err)
	}
	getResponse, err := http.Get(downloadURL)
	if err != nil {
		t.Fatal(err)
	}
	getResponse.Body.Close()
	if getResponse.StatusCode != http.StatusOK {
		t.Fatalf("GET returned %s", getResponse.Status)
	}

	if err := service.DeleteObjects(ctx, []string{upload.ObjectKey}); err != nil {
		t.Fatal(err)
	}
	remaining, err := service.ListObjects(ctx, upload.ObjectKey)
	if err != nil {
		t.Fatal(err)
	}
	if len(remaining) != 0 {
		t.Fatalf("object still present after delete: %+v", remaining)
	}
}
