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
	// loc is omitted: the driver's default location is already UTC.
	for _, fragment := range []string{"oops:secret@tcp(localhost:3306)/oops", "parseTime=true", "charset=utf8mb4"} {
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
	for _, fragment := range []string{"tcp(db.example.com:3306)", "/oops", "parseTime=true", "charset=utf8mb4"} {
		if !strings.Contains(dsn, fragment) {
			t.Errorf("jdbc-converted DSN missing %q: %s", fragment, dsn)
		}
	}

	native := &Config{}
	native.Spring.Datasource.URL = "oops:secret@tcp(localhost:3306)/oops?parseTime=true"
	dsn, err = native.MySQLDSN()
	if err != nil {
		t.Fatal(err)
	}
	if dsn != native.Spring.Datasource.URL {
		t.Errorf("native DSN must pass through unchanged, got %s", dsn)
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
	if dsn != cfg.Datasource.URL {
		t.Errorf("top-level datasource must win, got %s", dsn)
	}
}
