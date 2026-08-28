package httpapi

import "github.com/gin-gonic/gin"

// Routes builds the full /api + /openapi surface. Registration is grouped the
// way the Java controllers were: one block per resource, public → authed →
// admin, with the application aggregate's sub-resources kept together.
func (s *Server) Routes() *gin.Engine {
	gin.SetMode(gin.ReleaseMode) // silence the per-handler registration debug prints
	engine := gin.New()
	engine.Use(gin.Recovery(), s.cors())

	api := engine.Group("/api")
	s.registerPublicRoutes(api)

	authed := api.Group("", s.requireAuth())
	s.registerUserRoutes(authed)
	s.registerPlatformRoutes(authed)
	s.registerApplicationRoutes(authed)
	s.registerSandbox(authed)
	if s.cfg.Oops.IDE.Enabled {
		s.registerIDERoutes(authed)
	}

	s.registerOpenAPI(engine)
	return engine
}

// No auth: health, login, feature flags, and the OAuth entry points.
func (s *Server) registerPublicRoutes(api *gin.RouterGroup) {
	api.GET("/health", s.health)
	api.POST("/auth/login", s.login)
	api.GET("/features", s.features)
	api.GET("/auth/external/providers", s.externalProviders)
	api.GET("/auth/external/:provider/redirect", s.externalRedirect)
	api.POST("/auth/external/:provider/callback", s.externalCallback)
}

// Accounts: self-service plus the ADMIN-only user management.
func (s *Server) registerUserRoutes(authed *gin.RouterGroup) {
	authed.GET("/users", s.listUsers)
	authed.GET("/users/page", s.listUsersPage)
	authed.GET("/users/me", s.me)
	authed.PUT("/users/me", s.updateMyProfile)
	authed.PUT("/users/me/password", s.changeMyPassword)
	authed.POST("/users/me/access-token/reset", s.resetMyAccessToken)

	admin := authed.Group("", s.requireAdmin())
	admin.POST("/users", s.createUser)
	admin.PUT("/users/:id", s.updateUser)
	admin.DELETE("/users/:id", s.deleteUser)
}

// Platform resources that are not application-scoped: namespaces,
// environments, domains, cluster nodes, validations, cron preview, assets,
// and the cross-namespace queries.
func (s *Server) registerPlatformRoutes(authed *gin.RouterGroup) {
	admin := authed.Group("", s.requireAdmin())

	authed.GET("/namespaces", s.listNamespaces)
	admin.POST("/namespaces", s.createNamespace)
	admin.PUT("/namespaces", s.updateNamespace)

	authed.GET("/environments", s.listEnvironments)
	authed.GET("/environments/:id", s.getEnvironment)
	admin.POST("/environments", s.createEnvironment)
	admin.PUT("/environments/:id/cluster", s.updateEnvironmentCluster)
	admin.PUT("/environments/:id/credentials", s.updateEnvironmentCredentials)
	admin.DELETE("/environments/:id", s.deleteEnvironment)

	authed.GET("/domains", s.listDomains)
	authed.GET("/domains/:id", s.getDomain)
	admin.POST("/domains", s.createDomain)
	admin.PUT("/domains/:id", s.updateDomain)
	admin.DELETE("/domains/:id", s.deleteDomain)

	authed.GET("/nodes", s.listNodes)
	admin.POST("/nodes/:name/schedulable", s.setNodeSchedulable)
	authed.POST("/kubernetes/validations", s.validateKubernetes)
	authed.POST("/kubernetes/namespaces", s.createKubernetesNamespace)
	authed.POST("/image-repositories/validations", s.validateImageRepository)

	authed.GET("/cron/next", s.cronNext)

	authed.GET("/assets", s.listAssets)
	authed.POST("/assets/upload-url", s.createAssetUploadURL)
	admin.DELETE("/assets", s.deleteAsset)

	authed.GET("/search/applications", s.searchApplications)
	authed.POST("/index/pipelines", s.queryPipelinesIndex)
	authed.POST("/index/applications", s.queryApplicationsIndex)
}

// The application aggregate: profile, configs, runtime views, deployments,
// pipelines, pods and the danger zone.
func (s *Server) registerApplicationRoutes(authed *gin.RouterGroup) {
	authed.GET("/namespaces/:namespace/applications", s.listApplications)
	authed.POST("/namespaces/:namespace/applications", s.createApplication)
	authed.GET("/namespaces/:namespace/applications/active-deployments", s.activeDeployments)

	application := authed.Group("/namespaces/:namespace/applications/:name")
	application.GET("", s.getApplication)
	application.PUT("", s.updateApplication)

	// Per-application configuration.
	application.GET("/build/config", s.getBuildConfig)
	application.PUT("/build/config", s.updateBuildConfig)
	application.GET("/branches", s.getApplicationBranches)
	application.GET("/environments", s.getEnvironmentBindings)
	application.PUT("/environments", s.updateEnvironmentBindings)
	application.GET("/environments/build/configs", s.getBuildEnvironmentConfigs)
	application.PUT("/environments/build/configs", s.updateBuildEnvironmentConfigs)
	application.GET("/environments/runtime-specs", s.getRuntimeSpecEnvironmentConfigs)
	application.PUT("/environments/runtime-specs", s.updateRuntimeSpecEnvironmentConfigs)
	application.GET("/runtime-spec", s.getRuntimeSpec)
	application.PUT("/runtime-spec", s.updateRuntimeSpec)
	application.GET("/service", s.getServiceConfig)
	application.PUT("/service", s.updateServiceConfig)
	application.GET("/service/host-check", s.checkServiceHost)
	application.GET("/service/cluster-domain", s.getClusterDomain)
	application.GET("/expert-config", s.getExpertConfig)
	application.PUT("/expert-config", s.updateExpertConfig)
	application.GET("/configmaps", s.getConfigMaps)
	application.PUT("/configmaps", s.updateConfigMaps)

	// Runtime views.
	application.GET("/status", s.getApplicationStatus)
	application.GET("/status/watch", s.watchApplicationStatus)
	application.GET("/events", s.getApplicationEvents)
	application.GET("/metrics", s.getApplicationMetrics)
	application.GET("/metrics/history", s.getApplicationMetricsHistory)
	application.GET("/resources", s.getApplicationResources)
	application.GET("/current-image", s.getCurrentImage)

	// Deployments and pipelines.
	application.POST("/deployments", s.deployApplication)
	application.POST("/deployments/source-upload", s.createBuildSourceUpload)
	application.GET("/pipelines", s.listPipelines)
	application.GET("/pipelines/:id", s.getPipeline)
	application.POST("/pipelines/:id/deploy", s.manualDeployPipeline)
	application.PUT("/pipelines/:id/stop", s.stopPipeline)
	application.POST("/pipelines/:id/rollback", s.rollbackPipeline)
	application.GET("/pipelines/:id/log", s.pipelineLogWebSocket)
	application.GET("/last-successful-pipeline", s.getLastSuccessfulPipeline)

	// Pods: restart, streams, file browser.
	application.PUT("/pods/:pod/restart", s.restartApplicationPod)
	application.GET("/pods/:pod/log", s.podLogWebSocket)
	application.GET("/pods/:pod/terminal", s.terminalWebSocket)
	podFiles := application.Group("/pods/:pod/files")
	podFiles.GET("", s.podFSList)
	podFiles.GET("/download", s.podFSDownload)
	podFiles.GET("/content", s.podFSContent)
	podFiles.PUT("/content", s.podFSSaveContent)
	podFiles.POST("/upload", s.podFSUpload)
	podFiles.DELETE("", s.podFSDelete)
	podFiles.POST("/directory", s.podFSMkdir)
	podFiles.POST("/rename", s.podFSRename)

	// Danger zone.
	application.DELETE("", s.deleteApplication)
	application.POST("/namespace-migration", s.migrateNamespace)
}

// IDE instances, present only when oops.ide.enabled (like the conditional beans).
func (s *Server) registerIDERoutes(authed *gin.RouterGroup) {
	ides := authed.Group("/namespaces/:namespace/applications/:name/ides")
	ides.GET("", s.listIdes)
	ides.POST("", s.createIDE)
	ides.DELETE("/:ide", s.deleteIDE)
	ides.GET("/config/default", s.getDefaultIDEConfig)
}
