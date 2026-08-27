// Package httpapi serves the /api surface with wire-compatible responses, so
// the existing Next.js frontend works unchanged against this backend.
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
	return &Server{cfg: cfg, store: st, codec: crypto.NewCodec(cfg.Oops.Crypto.SecretKey), engine: pipelineEngine, storage: storage}
}

// Engine exposes the pipeline engine so main can start its scan loops.
func (s *Server) Engine() *engine.Engine { return s.engine }

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

func (s *Server) Handler() *gin.Engine {
	engine := gin.New()
	engine.Use(gin.Recovery(), s.cors())

	api := engine.Group("/api")
	api.GET("/health", s.health)
	api.POST("/auth/login", s.login)
	api.GET("/features", s.features)
	api.GET("/auth/external/providers", s.externalProviders)
	api.GET("/auth/external/:provider/redirect", s.externalRedirect)
	api.POST("/auth/external/:provider/callback", s.externalCallback)

	authed := api.Group("", s.requireAuth())
	authed.GET("/users/me", s.me)
	authed.GET("/users", s.listUsers)
	authed.GET("/users/page", s.listUsersPage)
	authed.GET("/domains", s.listDomains)
	authed.GET("/namespaces", s.listNamespaces)
	authed.GET("/environments", s.listEnvironments)
	authed.GET("/nodes", s.listNodes)
	authed.GET("/namespaces/:namespace/applications", s.listApplications)
	authed.GET("/namespaces/:namespace/applications/active-deployments", s.activeDeployments)

	authed.POST("/namespaces/:namespace/applications", s.createApplication)

	application := authed.Group("/namespaces/:namespace/applications/:name")
	application.GET("", s.getApplication)
	application.PUT("", s.updateApplication)
	application.PUT("/build/config", s.updateBuildConfig)
	application.PUT("/environments", s.updateEnvironmentBindings)
	application.PUT("/environments/build/configs", s.updateBuildEnvironmentConfigs)
	application.PUT("/environments/runtime-specs", s.updateRuntimeSpecEnvironmentConfigs)
	application.PUT("/runtime-spec", s.updateRuntimeSpec)
	application.PUT("/service", s.updateServiceConfig)
	application.PUT("/expert-config", s.updateExpertConfig)
	application.GET("/build/config", s.getBuildConfig)
	application.GET("/branches", s.getApplicationBranches)
	application.GET("/environments", s.getEnvironmentBindings)
	application.GET("/environments/build/configs", s.getBuildEnvironmentConfigs)
	application.GET("/environments/runtime-specs", s.getRuntimeSpecEnvironmentConfigs)
	application.GET("/runtime-spec", s.getRuntimeSpec)
	application.GET("/service", s.getServiceConfig)
	application.GET("/expert-config", s.getExpertConfig)
	application.GET("/pipelines", s.listPipelines)
	application.GET("/pipelines/:id", s.getPipeline)
	application.POST("/deployments", s.deployApplication)
	application.POST("/deployments/source-upload", s.createBuildSourceUpload)
	application.POST("/pipelines/:id/deploy", s.manualDeployPipeline)
	application.PUT("/pipelines/:id/stop", s.stopPipeline)
	application.POST("/pipelines/:id/rollback", s.rollbackPipeline)
	application.GET("/pipelines/:id/log", s.pipelineLogWebSocket)
	application.DELETE("", s.deleteApplication)
	application.POST("/namespace-migration", s.migrateNamespace)
	application.GET("/status", s.getApplicationStatus)
	application.GET("/status/watch", s.watchApplicationStatus)
	application.GET("/events", s.getApplicationEvents)
	application.GET("/metrics", s.getApplicationMetrics)
	application.GET("/metrics/history", s.getApplicationMetricsHistory)
	application.GET("/service/cluster-domain", s.getClusterDomain)
	application.PUT("/pods/:pod/restart", s.restartApplicationPod)
	podFiles := application.Group("/pods/:pod/files")
	podFiles.GET("", s.podFSList)
	podFiles.GET("/download", s.podFSDownload)
	podFiles.GET("/content", s.podFSContent)
	podFiles.PUT("/content", s.podFSSaveContent)
	podFiles.POST("/upload", s.podFSUpload)
	podFiles.DELETE("", s.podFSDelete)
	podFiles.POST("/directory", s.podFSMkdir)
	podFiles.POST("/rename", s.podFSRename)
	application.GET("/configmaps", s.getConfigMaps)
	application.PUT("/configmaps", s.updateConfigMaps)
	application.GET("/pods/:pod/log", s.podLogWebSocket)
	application.GET("/pods/:pod/terminal", s.terminalWebSocket)
	application.GET("/resources", s.getApplicationResources)
	application.GET("/current-image", s.getCurrentImage)
	application.GET("/service/host-check", s.checkServiceHost)
	application.GET("/last-successful-pipeline", s.getLastSuccessfulPipeline)

	authed.POST("/index/pipelines", s.queryPipelinesIndex)
	authed.POST("/index/applications", s.queryApplicationsIndex)

	if s.cfg.Oops.IDE.Enabled {
		ides := authed.Group("/namespaces/:namespace/applications/:name/ides")
		ides.GET("", s.listIdes)
		ides.POST("", s.createIde)
		ides.DELETE("/:ide", s.deleteIde)
		ides.GET("/config/default", s.getDefaultIdeConfig)
	}
	s.registerSandbox(authed)
	s.registerOpenAPI(engine)
	authed.GET("/search/applications", s.searchApplications)

	authed.PUT("/users/me", s.updateMyProfile)
	authed.PUT("/users/me/password", s.changeMyPassword)
	authed.POST("/users/me/access-token/reset", s.resetMyAccessToken)
	authed.GET("/cron/next", s.cronNext)
	authed.GET("/assets", s.listAssets)
	authed.POST("/assets/upload-url", s.createAssetUploadURL)
	admin3 := authed.Group("", s.requireAdmin())
	admin3.DELETE("/assets", s.deleteAsset)

	admin := authed.Group("", s.requireAdmin())
	admin.POST("/namespaces", s.createNamespace)
	admin.PUT("/namespaces", s.updateNamespace)
	authed.GET("/domains/:id", s.getDomain)
	authed.GET("/environments/:id", s.getEnvironment)
	admin2 := authed.Group("", s.requireAdmin())
	admin2.POST("/environments", s.createEnvironment)
	admin2.PUT("/environments/:id/cluster", s.updateEnvironmentCluster)
	admin2.PUT("/environments/:id/credentials", s.updateEnvironmentCredentials)
	admin2.DELETE("/environments/:id", s.deleteEnvironment)
	authed.POST("/kubernetes/validations", s.validateKubernetes)
	authed.POST("/kubernetes/namespaces", s.createKubernetesNamespace)
	authed.POST("/image-repositories/validations", s.validateImageRepository)
	admin.POST("/domains", s.createDomain)
	admin.PUT("/domains/:id", s.updateDomain)
	admin.DELETE("/domains/:id", s.deleteDomain)
	admin.POST("/users", s.createUser)
	admin.PUT("/users/:id", s.updateUser)
	admin.DELETE("/users/:id", s.deleteUser)

	return engine
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
