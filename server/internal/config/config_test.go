package config

import (
	"os"
	"path/filepath"
	"strings"
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
	// loc=Local: the datetime columns hold a naive wall clock in the process zone.
	for _, fragment := range []string{"oops:secret@tcp(localhost:3306)/oops", "parseTime=true", "charset=utf8mb4", "loc=Local"} {
		if !strings.Contains(dsn, fragment) {
			t.Fatalf("dsn missing %q: %s", fragment, dsn)
		}
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

func TestMySQLDSNFormats(t *testing.T) {
	jdbc := &Config{}
	jdbc.Spring.Datasource.URL = "jdbc:mysql://db.example.com:3306/oops?useSSL=false&serverTimezone=UTC"
	jdbc.Spring.Datasource.Username = "oops"
	jdbc.Spring.Datasource.Password = "p@ss/word" // must survive escaping
	dsn, err := jdbc.MySQLDSN()
	if err != nil {
		t.Fatal(err)
	}
	for _, fragment := range []string{"tcp(db.example.com:3306)", "/oops", "parseTime=true", "charset=utf8mb4", "loc=Local"} {
		if !strings.Contains(dsn, fragment) {
			t.Errorf("jdbc-converted DSN missing %q: %s", fragment, dsn)
		}
	}

	// A native DSN keeps its own credentials and address, but the timestamp
	// zone is not the operator's to get wrong: loc=Local is forced on.
	native := &Config{}
	native.Spring.Datasource.URL = "oops:secret@tcp(localhost:3306)/oops?loc=UTC"
	dsn, err = native.MySQLDSN()
	if err != nil {
		t.Fatal(err)
	}
	for _, fragment := range []string{"oops:secret@tcp(localhost:3306)/oops", "parseTime=true", "loc=Local"} {
		if !strings.Contains(dsn, fragment) {
			t.Errorf("native DSN missing %q: %s", fragment, dsn)
		}
	}
	if strings.Contains(dsn, "loc=UTC") {
		t.Errorf("native DSN must not stay pinned to UTC: %s", dsn)
	}

	broken := &Config{}
	broken.Spring.Datasource.URL = "not a dsn at all"
	if _, err := broken.MySQLDSN(); err == nil {
		t.Error("garbage must be rejected")
	}
}

func TestDatasourceTopLevelWinsOverSpring(t *testing.T) {
	cfg := &Config{}
	cfg.Datasource.URL = "native:pw@tcp(db:3306)/oops?parseTime=true"
	cfg.Spring.Datasource.URL = "jdbc:mysql://old:3306/oops"
	dsn, err := cfg.MySQLDSN()
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(dsn, "native:pw@tcp(db:3306)/oops") {
		t.Errorf("top-level datasource must win, got %s", dsn)
	}
}
