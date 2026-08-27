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
