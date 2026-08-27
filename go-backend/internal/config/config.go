// Package config loads the same config/application.yml the Java backend uses,
// so a single configuration file drives both processes during the migration.
package config

import (
	"fmt"
	"net/url"
	"os"
	"regexp"
	"strings"

	"gopkg.in/yaml.v3"
)

type Config struct {
	Spring struct {
		Datasource struct {
			URL      string `yaml:"url"`
			Username string `yaml:"username"`
			Password string `yaml:"password"`
		} `yaml:"datasource"`
	} `yaml:"spring"`
	Oops struct {
		JWT struct {
			Secret     string `yaml:"secret"`
			Expiration int64  `yaml:"expiration"` // milliseconds, same as Java
		} `yaml:"jwt"`
		Crypto struct {
			SecretKey string `yaml:"secret-key"`
		} `yaml:"crypto"`
		Feishu struct {
			Enabled bool `yaml:"enabled"`
		} `yaml:"feishu"`
		IDE struct {
			Enabled bool   `yaml:"enabled"`
			Domain  string `yaml:"domain"`
			HTTPS   bool   `yaml:"https"`
		} `yaml:"ide"`
		ObjectStorage struct {
			Enabled bool `yaml:"enabled"`
		} `yaml:"object-storage"`
	} `yaml:"oops"`
}

func Load(path string) (*Config, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read config: %w", err)
	}
	var cfg Config
	if err := yaml.Unmarshal(raw, &cfg); err != nil {
		return nil, fmt.Errorf("parse config: %w", err)
	}
	if len(cfg.Oops.JWT.Secret) < 32 {
		return nil, fmt.Errorf("oops.jwt.secret must be at least 32 chars")
	}
	return &cfg, nil
}

var jdbcPattern = regexp.MustCompile(`^jdbc:mysql://([^/]+)/([^?]+)(?:\?(.*))?$`)

// MySQLDSN converts the Spring JDBC URL into a go-sql-driver DSN.
func (c *Config) MySQLDSN() (string, error) {
	matches := jdbcPattern.FindStringSubmatch(c.Spring.Datasource.URL)
	if matches == nil {
		return "", fmt.Errorf("unsupported datasource url: %s", c.Spring.Datasource.URL)
	}
	hostPort, database := matches[1], matches[2]
	params := url.Values{}
	params.Set("parseTime", "true")
	params.Set("charset", "utf8mb4")
	// Keep timestamps interpreted the same way as the JVM side (serverTimezone=UTC).
	if strings.Contains(matches[3], "serverTimezone=UTC") {
		params.Set("loc", "UTC")
	}
	return fmt.Sprintf("%s:%s@tcp(%s)/%s?%s",
		c.Spring.Datasource.Username, c.Spring.Datasource.Password, hostPort, database, params.Encode()), nil
}
