package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestMySQLDSNFromJDBCURL(t *testing.T) {
	cfg := &Config{}
	cfg.Spring.Datasource.URL = "jdbc:mysql://localhost:3306/oops?useSSL=false&serverTimezone=UTC&characterEncoding=utf8"
	cfg.Spring.Datasource.Username = "oops"
	cfg.Spring.Datasource.Password = "secret"

	dsn, err := cfg.MySQLDSN()
	if err != nil {
		t.Fatal(err)
	}
	want := "oops:secret@tcp(localhost:3306)/oops?charset=utf8mb4&loc=UTC&parseTime=true"
	if dsn != want {
		t.Fatalf("dsn = %q, want %q", dsn, want)
	}
}

func TestLoadRejectsShortJWTSecret(t *testing.T) {
	path := filepath.Join(t.TempDir(), "application.yml")
	yml := "spring:\n  datasource:\n    url: jdbc:mysql://h:3306/db\noops:\n  jwt:\n    secret: short\n"
	if err := os.WriteFile(path, []byte(yml), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(path); err == nil {
		t.Fatal("expected error for short jwt secret")
	}
}
