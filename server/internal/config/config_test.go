package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func write(t *testing.T, content string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "oops.yaml")
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

const minimal = `
database: {host: mysql, user: root, password: x}
jwt: {secret: 0123456789abcdef0123456789abcdef}
crypto: {secret_key: k}
pipeline:
  images: {clone: a, zip: b, push: c}
`

func TestLoadMinimal(t *testing.T) {
	cfg, err := Load(write(t, minimal))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Server.Port != 8080 || cfg.Database.Port != 3306 || cfg.Admin.Password != "admin123" {
		t.Fatalf("defaults not applied: %+v", cfg)
	}
	if !strings.Contains(cfg.Database.DSN(), "loc=Local") || !strings.Contains(cfg.Database.DSN(), "parseTime=true") {
		t.Fatalf("dsn must pin local wall clock: %s", cfg.Database.DSN())
	}
}

func TestUnknownKeyIsAnError(t *testing.T) {
	_, err := Load(write(t, minimal+"\nspring: {datasource: x}\n"))
	if err == nil || !strings.Contains(err.Error(), "spring") {
		t.Fatalf("expected unknown-key error, got %v", err)
	}
}

func TestMissingRequiredIsAnError(t *testing.T) {
	_, err := Load(write(t, "server: {port: 1}\n"))
	if err == nil || !strings.Contains(err.Error(), "database.host") || !strings.Contains(err.Error(), "jwt.secret") {
		t.Fatalf("expected required-key errors, got %v", err)
	}
}

func TestMissingFileIsAnError(t *testing.T) {
	if _, err := Load(filepath.Join(t.TempDir(), "nope.yaml")); err == nil {
		t.Fatal("expected error for missing file")
	}
}
