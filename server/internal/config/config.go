// Package config loads the OOPS server configuration.
//
// The file is a new, strict YAML format: unknown keys are an error, and every
// key listed as required must be present. There is deliberately no
// compatibility with the Spring application.yml of the Java backend.
package config

import (
	"errors"
	"fmt"
	"os"
	"strings"
	"time"

	"gopkg.in/yaml.v3"
)

type Config struct {
	Server        Server        `yaml:"server"`
	Database      Database      `yaml:"database"`
	Admin         Admin         `yaml:"admin"`
	JWT           JWT           `yaml:"jwt"`
	Crypto        Crypto        `yaml:"crypto"`
	Pipeline      Pipeline      `yaml:"pipeline"`
	Ingress       Ingress       `yaml:"ingress"`
	Metrics       Metrics       `yaml:"metrics"`
	PodFilesystem PodFilesystem `yaml:"pod_filesystem"`
	Feishu        Feishu        `yaml:"feishu"`
	IDE           IDE           `yaml:"ide"`
	ObjectStorage ObjectStorage `yaml:"object_storage"`
	Sandbox       Sandbox       `yaml:"sandbox"`
}

type Server struct {
	Port int `yaml:"port"`
}

type Database struct {
	Host     string `yaml:"host"`
	Port     int    `yaml:"port"`
	Name     string `yaml:"name"`
	User     string `yaml:"user"`
	Password string `yaml:"password"`
	// MaxOpenConns is optional (0 = driver default).
	MaxOpenConns int `yaml:"max_open_conns"`
}

// DSN builds the go-sql-driver DSN. parseTime and loc=Local are forced: the
// datetime(6) columns hold a naive local wall clock and must never be shifted.
func (d Database) DSN() string {
	return fmt.Sprintf("%s:%s@tcp(%s:%d)/%s?parseTime=true&loc=Local&charset=utf8mb4&collation=utf8mb4_unicode_ci&multiStatements=true",
		d.User, d.Password, d.Host, d.Port, d.Name)
}

type Admin struct {
	Password string `yaml:"password"`
}

type JWT struct {
	Secret     string        `yaml:"secret"`
	Expiration time.Duration `yaml:"expiration"`
}

type Crypto struct {
	SecretKey string `yaml:"secret_key"`
}

type Pipeline struct {
	Images          PipelineImages    `yaml:"images"`
	RegistryMirrors map[string]string `yaml:"registry_mirrors"`
	UnzipExcludes   []string          `yaml:"unzip_excludes"`
}

type PipelineImages struct {
	Clone string `yaml:"clone"`
	Zip   string `yaml:"zip"`
	Push  string `yaml:"push"`
}

type Ingress struct {
	CertResolver string `yaml:"cert_resolver"`
}

type Metrics struct {
	History MetricsHistory `yaml:"history"`
	Alert   MetricsAlert   `yaml:"alert"`
}

type MetricsHistory struct {
	IntervalSeconds int            `yaml:"interval_seconds"`
	MaxRangeHours   int            `yaml:"max_range_hours"`
	Backend         MetricsBackend `yaml:"backend"`
}

type MetricsBackend struct {
	Namespace   string `yaml:"namespace"`
	ServiceName string `yaml:"service_name"`
	Port        int    `yaml:"port"`
}

// Configured reports whether a Prometheus backend is addressable.
func (b MetricsBackend) Configured() bool {
	return strings.TrimSpace(b.Namespace) != "" && strings.TrimSpace(b.ServiceName) != "" && b.Port > 0
}

// Describe renders "namespace/service:port".
func (b MetricsBackend) Describe() string {
	return fmt.Sprintf("%s/%s:%d", b.Namespace, b.ServiceName, b.Port)
}

type MetricsAlert struct {
	Enabled               bool      `yaml:"enabled"`
	CPU                   AlertRule `yaml:"cpu"`
	Memory                AlertRule `yaml:"memory"`
	RepeatIntervalMinutes int       `yaml:"repeat_interval_minutes"`
}

type AlertRule struct {
	ThresholdPercent int `yaml:"threshold_percent"`
	SustainedMinutes int `yaml:"sustained_minutes"`
}

type PodFilesystem struct {
	MaxDownloadSizeBytes int64 `yaml:"max_download_size_bytes"`
	MaxUploadSizeBytes   int64 `yaml:"max_upload_size_bytes"`
	MaxEditSizeBytes     int64 `yaml:"max_edit_size_bytes"`
}

type Feishu struct {
	Enabled              bool   `yaml:"enabled"`
	AppID                string `yaml:"app_id"`
	AppSecret            string `yaml:"app_secret"`
	RedirectURI          string `yaml:"redirect_uri"`
	SyncUserDeactivation bool   `yaml:"sync_user_deactivation"`
}

type IDE struct {
	Enabled     bool     `yaml:"enabled"`
	Domain      string   `yaml:"domain"`
	HTTPS       bool     `yaml:"https"`
	Image       string   `yaml:"image"`
	Middlewares []string `yaml:"middlewares"`
}

type ObjectStorage struct {
	Enabled                      bool   `yaml:"enabled"`
	Endpoint                     string `yaml:"endpoint"`
	Region                       string `yaml:"region"`
	Bucket                       string `yaml:"bucket"`
	AccessKey                    string `yaml:"access_key"`
	SecretKey                    string `yaml:"secret_key"`
	PathStyleAccess              bool   `yaml:"path_style_access"`
	KeyPrefix                    string `yaml:"key_prefix"`
	AssetKeyPrefix               string `yaml:"asset_key_prefix"`
	AssetBaseURL                 string `yaml:"asset_base_url"`
	UploadURLExpirationSeconds   int64  `yaml:"upload_url_expiration_seconds"`
	DownloadURLExpirationSeconds int64  `yaml:"download_url_expiration_seconds"`
	MaxFileSizeBytes             int64  `yaml:"max_file_size_bytes"`
}

type Sandbox struct {
	Images []string `yaml:"images"`
}

// Load reads and validates the configuration file at path. Any problem is a
// startup error: there is no fallback and no compatibility layer.
func Load(path string) (*Config, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read config %s: %w", path, err)
	}
	cfg := Defaults()
	decoder := yaml.NewDecoder(strings.NewReader(string(raw)))
	decoder.KnownFields(true)
	if err := decoder.Decode(&cfg); err != nil && !errors.Is(err, os.ErrNotExist) {
		return nil, fmt.Errorf("parse config %s: %w", path, err)
	}
	if err := cfg.Validate(); err != nil {
		return nil, fmt.Errorf("invalid config %s: %w", path, err)
	}
	return &cfg, nil
}

// Defaults are the values applied before the file is read. Only settings the
// Java backend also defaulted have one; everything else is required.
func Defaults() Config {
	return Config{
		Server:   Server{Port: 8080},
		Database: Database{Port: 3306, Name: "oops"},
		Admin:    Admin{Password: "admin123"},
		JWT:      JWT{Expiration: 7 * 24 * time.Hour},
		Pipeline: Pipeline{
			RegistryMirrors: map[string]string{"docker.io": "docker.m.daocloud.io"},
			UnzipExcludes: []string{".git/*", "*/.git/*", "node_modules/*", "*/node_modules/*",
				"__MACOSX", "__MACOSX/*", "*/__MACOSX", "*/__MACOSX/*", ".DS_Store", "*/.DS_Store"},
		},
		Metrics: Metrics{
			History: MetricsHistory{IntervalSeconds: 30, MaxRangeHours: 24,
				Backend: MetricsBackend{Namespace: "monitoring", ServiceName: "prometheus-operated", Port: 9090}},
			Alert: MetricsAlert{CPU: AlertRule{95, 10}, Memory: AlertRule{90, 5}, RepeatIntervalMinutes: 30},
		},
		PodFilesystem: PodFilesystem{MaxDownloadSizeBytes: 52428800, MaxUploadSizeBytes: 52428800, MaxEditSizeBytes: 1048576},
		ObjectStorage: ObjectStorage{Region: "cn-hangzhou", KeyPrefix: "oops-package", AssetKeyPrefix: "oops-assets",
			UploadURLExpirationSeconds: 900, DownloadURLExpirationSeconds: 1800, MaxFileSizeBytes: 524288000},
	}
}

// Validate enforces the required keys and basic sanity.
func (c *Config) Validate() error {
	var problems []string
	need := func(ok bool, msg string) {
		if !ok {
			problems = append(problems, msg)
		}
	}
	need(c.Server.Port > 0 && c.Server.Port < 65536, "server.port must be 1-65535")
	need(c.Database.Host != "", "database.host is required")
	need(c.Database.User != "", "database.user is required")
	need(c.Database.Name != "", "database.name is required")
	need(len(c.JWT.Secret) >= 32, "jwt.secret is required and must be at least 32 characters")
	need(c.JWT.Expiration > 0, "jwt.expiration must be a positive duration (e.g. 168h)")
	need(c.Crypto.SecretKey != "", "crypto.secret_key is required")
	need(c.Pipeline.Images.Clone != "", "pipeline.images.clone is required")
	need(c.Pipeline.Images.Zip != "", "pipeline.images.zip is required")
	need(c.Pipeline.Images.Push != "", "pipeline.images.push is required")
	need(c.Metrics.History.IntervalSeconds > 0, "metrics.history.interval_seconds must be positive")
	need(c.Metrics.History.MaxRangeHours > 0, "metrics.history.max_range_hours must be positive")
	if c.Feishu.Enabled {
		need(c.Feishu.AppID != "" && c.Feishu.AppSecret != "", "feishu.app_id and feishu.app_secret are required when feishu.enabled")
	}
	if c.IDE.Enabled {
		need(c.IDE.Domain != "" && c.IDE.Image != "", "ide.domain and ide.image are required when ide.enabled")
	}
	if c.ObjectStorage.Enabled {
		need(c.ObjectStorage.Bucket != "" && c.ObjectStorage.AccessKey != "" && c.ObjectStorage.SecretKey != "",
			"object_storage.bucket, access_key and secret_key are required when object_storage.enabled")
	}
	if len(problems) > 0 {
		return errors.New(strings.Join(problems, "; "))
	}
	return nil
}
