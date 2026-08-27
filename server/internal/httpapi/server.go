// Package httpapi serves the /api and /openapi surfaces with wire-compatible
// responses, so the existing Next.js frontend and CLI work unchanged.
package httpapi

import (
	"github.com/gin-gonic/gin"

	"github.com/wellch4n/oops/server/internal/config"
	"github.com/wellch4n/oops/server/internal/crypto"
	"github.com/wellch4n/oops/server/internal/engine"
	"github.com/wellch4n/oops/server/internal/feishu"
	"github.com/wellch4n/oops/server/internal/k8s"
	"github.com/wellch4n/oops/server/internal/objectstorage"
	"github.com/wellch4n/oops/server/internal/store"
)

type Server struct {
	cfg     *config.Config
	store   *store.Store
	codec   *crypto.Codec
	engine  *engine.Engine
	storage *objectstorage.Storage
}

func NewServer(cfg *config.Config, st *store.Store) *Server {
	pipelineEngine := &engine.Engine{
		Store: st,
		Images: engine.ImageConfig{
			Clone:           cfg.Oops.Pipeline.Image.Clone,
			Zip:             cfg.Oops.Pipeline.Image.Zip,
			Push:            cfg.Oops.Pipeline.Image.Push,
			RegistryMirrors: cfg.Oops.Pipeline.Image.RegistryMirrors,
			UnzipExcludes:   cfg.Oops.Pipeline.Image.UnzipExcludes,
		},
		CertResolver: cfg.Oops.Ingress.CertResolver,
	}
	if cfg.Oops.Feishu.Enabled {
		pipelineEngine.Notifier = &feishuNotifier{
			store:  st,
			client: feishu.NewClient(cfg.Oops.Feishu.AppID, cfg.Oops.Feishu.AppSecret),
		}
	}

	storageConfig := cfg.Oops.ObjectStorage
	storage, err := objectstorage.New(objectstorage.Config{
		Enabled: storageConfig.Enabled, Endpoint: storageConfig.Endpoint, Region: storageConfig.Region,
		Bucket: storageConfig.Bucket, AccessKey: storageConfig.AccessKey, SecretKey: storageConfig.SecretKey,
		PathStyleAccess: storageConfig.PathStyleAccess, KeyPrefix: storageConfig.KeyPrefix,
		AssetKeyPrefix: storageConfig.AssetKeyPrefix, AssetBaseURL: storageConfig.AssetBaseURL,
		UploadURLExpirationSeconds:   storageConfig.UploadURLExpirationSeconds,
		DownloadURLExpirationSeconds: storageConfig.DownloadURLExpirationSeconds,
		MaxFileSizeBytes:             storageConfig.MaxFileSizeBytes,
	})
	if err != nil {
		storage, _ = objectstorage.New(objectstorage.Config{})
	}
	pipelineEngine.ResolveZipURL = storage.ResolveDownloadURL

	return &Server{
		cfg:     cfg,
		store:   st,
		codec:   crypto.NewCodec(cfg.Oops.Crypto.SecretKey),
		engine:  pipelineEngine,
		storage: storage,
	}
}

// Engine exposes the pipeline engine so main can start its scan loops.
func (s *Server) Engine() *engine.Engine { return s.engine }

// Handler builds the router; kept as the historical entry point for main.
func (s *Server) Handler() *gin.Engine { return s.Routes() }

// AlertConfig assembles the engine's resource-alert configuration.
func (s *Server) AlertConfig() engine.AlertConfig {
	alert := s.cfg.Oops.Metrics.Alert
	history := s.cfg.Oops.Metrics.History
	return engine.AlertConfig{
		Enabled:               alert.Enabled,
		CPUThresholdPercent:   alert.CPU.ThresholdPercent,
		CPUSustainedMinutes:   alert.CPU.SustainedMinutes,
		MemThresholdPercent:   alert.Memory.ThresholdPercent,
		MemSustainedMinutes:   alert.Memory.SustainedMinutes,
		RepeatIntervalMinutes: alert.RepeatIntervalMinutes,
		IntervalSeconds:       history.IntervalSeconds,
		Backend: k8s.MetricsBackend{
			Namespace:   history.Backend.Namespace,
			ServiceName: history.Backend.ServiceName,
			Port:        history.Backend.Port,
		},
	}
}

// cors matches the Spring CORS setup so the dev frontend on :3000 can call us.
func (s *Server) cors() gin.HandlerFunc {
	return func(c *gin.Context) {
		if origin := c.GetHeader("Origin"); origin != "" {
			c.Header("Access-Control-Allow-Origin", origin)
			c.Header("Access-Control-Allow-Headers", "Authorization, Content-Type")
			c.Header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
			// The frontend's EventSource connects withCredentials.
			c.Header("Access-Control-Allow-Credentials", "true")
		}
		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(204)
			return
		}
		c.Next()
	}
}

func (s *Server) health(c *gin.Context) {
	c.JSON(200, ok("ok"))
}

func (s *Server) features(c *gin.Context) {
	c.JSON(200, ok(gin.H{
		"feishu":        s.cfg.Oops.Feishu.Enabled,
		"ide":           s.cfg.Oops.IDE.Enabled,
		"ideHost":       s.cfg.Oops.IDE.Domain,
		"ideHttps":      s.cfg.Oops.IDE.HTTPS,
		"objectStorage": s.cfg.Oops.ObjectStorage.Enabled,
	}))
}
