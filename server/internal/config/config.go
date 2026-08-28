// Package config loads the same config/application.yml the Java backend uses,
// so a single configuration file drives both processes during the migration.
package config

import (
	"fmt"
	"os"
	"regexp"
	"strings"
	"time"

	"github.com/go-sql-driver/mysql"
	"gopkg.in/yaml.v3"
)

// DatasourceConfig holds the database connection settings. URL accepts either
// a Spring JDBC URL or a native go-sql-driver DSN (see MySQLDSN).
type DatasourceConfig struct {
	URL      string `yaml:"url"`
	Username string `yaml:"username"`
	Password string `yaml:"password"`
}

type Config struct {
	// Datasource is the Go-native home for the database settings; the
	// spring.datasource block below is the Java-era spelling and is used
	// as a fallback when this one is absent.
	Datasource DatasourceConfig `yaml:"datasource"`
	Spring     struct {
		Datasource DatasourceConfig `yaml:"datasource"`
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
			Enabled              bool   `yaml:"enabled"`
			AppID                string `yaml:"app-id"`
			AppSecret            string `yaml:"app-secret"`
			RedirectURI          string `yaml:"redirect-uri"`
			CallbackFrontURL     string `yaml:"callback-front-url"`
			SyncUserDeactivation bool   `yaml:"sync-user-deactivation"`
		} `yaml:"feishu"`
		IDE struct {
			Enabled     bool     `yaml:"enabled"`
			Domain      string   `yaml:"domain"`
			HTTPS       bool     `yaml:"https"`
			Image       string   `yaml:"image"`
			Middlewares []string `yaml:"middlewares"`
		} `yaml:"ide"`
		ObjectStorage struct {
			Enabled                      bool   `yaml:"enabled"`
			Endpoint                     string `yaml:"endpoint"`
			Region                       string `yaml:"region"`
			Bucket                       string `yaml:"bucket"`
			AccessKey                    string `yaml:"access-key"`
			SecretKey                    string `yaml:"secret-key"`
			PathStyleAccess              bool   `yaml:"path-style-access"`
			KeyPrefix                    string `yaml:"key-prefix"`
			AssetKeyPrefix               string `yaml:"asset-key-prefix"`
			AssetBaseURL                 string `yaml:"asset-base-url"`
			UploadURLExpirationSeconds   int64  `yaml:"upload-url-expiration-seconds"`
			DownloadURLExpirationSeconds int64  `yaml:"download-url-expiration-seconds"`
			MaxFileSizeBytes             int64  `yaml:"max-file-size-bytes"`
		} `yaml:"object-storage"`
		Pipeline struct {
			Image struct {
				Clone           string            `yaml:"clone"`
				Zip             string            `yaml:"zip"`
				Push            string            `yaml:"push"`
				RegistryMirrors map[string]string `yaml:"registry-mirrors"`
				UnzipExcludes   []string          `yaml:"unzip-excludes"`
			} `yaml:"image"`
		} `yaml:"pipeline"`
		Ingress struct {
			CertResolver string `yaml:"cert-resolver"`
		} `yaml:"ingress"`
		Sandbox struct {
			Images []string `yaml:"images"`
		} `yaml:"sandbox"`
		Metrics struct {
			Alert struct {
				Enabled bool `yaml:"enabled"`
				CPU     struct {
					ThresholdPercent int `yaml:"threshold-percent"`
					SustainedMinutes int `yaml:"sustained-minutes"`
				} `yaml:"cpu"`
				Memory struct {
					ThresholdPercent int `yaml:"threshold-percent"`
					SustainedMinutes int `yaml:"sustained-minutes"`
				} `yaml:"memory"`
				RepeatIntervalMinutes int `yaml:"repeat-interval-minutes"`
			} `yaml:"alert"`
			History struct {
				IntervalSeconds int `yaml:"interval-seconds"`
				MaxRangeHours   int `yaml:"max-range-hours"`
				Backend         struct {
					Namespace   string `yaml:"namespace"`
					ServiceName string `yaml:"service-name"`
					Port        int    `yaml:"port"`
				} `yaml:"backend"`
			} `yaml:"history"`
		} `yaml:"metrics"`
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
	history := &cfg.Oops.Metrics.History
	if history.IntervalSeconds == 0 {
		history.IntervalSeconds = 30
	}
	if history.MaxRangeHours == 0 {
		history.MaxRangeHours = 24
	}
	alert := &cfg.Oops.Metrics.Alert
	if alert.CPU.ThresholdPercent == 0 {
		alert.CPU.ThresholdPercent = 95
	}
	if alert.CPU.SustainedMinutes == 0 {
		alert.CPU.SustainedMinutes = 10
	}
	if alert.Memory.ThresholdPercent == 0 {
		alert.Memory.ThresholdPercent = 90
	}
	if alert.Memory.SustainedMinutes == 0 {
		alert.Memory.SustainedMinutes = 5
	}
	if alert.RepeatIntervalMinutes == 0 {
		alert.RepeatIntervalMinutes = 60
	}
	backendDefaults := func(value *string, fallback string) {
		if *value == "" {
			*value = fallback
		}
	}
	// The defaults follow kube-prometheus-stack's layout; note an explicitly
	// blanked namespace in the yml cannot be told apart from an absent key
	// here, matching how a missing block behaves on the Java side.
	if history.Backend.Namespace == "" && history.Backend.ServiceName == "" && history.Backend.Port == 0 {
		backendDefaults(&history.Backend.Namespace, "monitoring")
		backendDefaults(&history.Backend.ServiceName, "prometheus-operated")
		history.Backend.Port = 9090
	}
	if cfg.Oops.Pipeline.Image.RegistryMirrors == nil {
		cfg.Oops.Pipeline.Image.RegistryMirrors = map[string]string{"docker.io": "docker.m.daocloud.io"}
	}
	if cfg.Oops.Pipeline.Image.UnzipExcludes == nil {
		cfg.Oops.Pipeline.Image.UnzipExcludes = []string{
			".git/*", "*/.git/*", "node_modules/*", "*/node_modules/*",
			"__MACOSX", "__MACOSX/*", "*/__MACOSX", "*/__MACOSX/*",
			".DS_Store", "*/.DS_Store",
		}
	}
	if len(cfg.Oops.JWT.Secret) < 32 {
		return nil, fmt.Errorf("oops.jwt.secret must be at least 32 chars")
	}
	return &cfg, nil
}

var jdbcPattern = regexp.MustCompile(`^jdbc:mysql://([^/]+)/([^?]+)(?:\?(.*))?$`)

// MySQLDSN accepts the datasource URL in either format: a Spring JDBC URL
// (jdbc:mysql://host:port/db?...) is converted to a go-sql-driver DSN, and a
// native DSN (user:pass@tcp(host:port)/db?...) is validated and passed
// through — username/password from the DSN itself win in that case.
func (c *Config) MySQLDSN() (string, error) {
	datasource := c.Datasource
	if strings.TrimSpace(datasource.URL) == "" {
		datasource = c.Spring.Datasource
	}
	datasourceURL := strings.TrimSpace(datasource.URL)
	if !strings.HasPrefix(datasourceURL, "jdbc:") {
		if _, err := mysql.ParseDSN(datasourceURL); err != nil {
			return "", fmt.Errorf("datasource url is neither a jdbc:mysql:// URL nor a valid DSN: %w", err)
		}
		return datasourceURL, nil
	}

	matches := jdbcPattern.FindStringSubmatch(datasourceURL)
	if matches == nil {
		return "", fmt.Errorf("unsupported datasource url: %s", datasourceURL)
	}
	settings := mysql.NewConfig() // handles credential escaping, unlike string concatenation
	settings.User = datasource.Username
	settings.Passwd = datasource.Password
	settings.Net = "tcp"
	settings.Addr = matches[1]
	settings.DBName = matches[2]
	settings.ParseTime = true
	settings.Params = map[string]string{"charset": "utf8mb4"}
	// Keep timestamps interpreted the same way as the JVM side (serverTimezone=UTC).
	if strings.Contains(matches[3], "serverTimezone=UTC") {
		settings.Loc = time.UTC
	}
	return settings.FormatDSN(), nil
}

// IDEMiddlewares returns the configured Traefik middleware names.
func (c *Config) IDEMiddlewares() []string {
	names := []string{}
	for _, name := range c.Oops.IDE.Middlewares {
		if trimmed := strings.TrimSpace(name); trimmed != "" {
			names = append(names, trimmed)
		}
	}
	return names
}
