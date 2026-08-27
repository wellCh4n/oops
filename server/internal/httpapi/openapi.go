package httpapi

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"

	"github.com/wellch4n/oops/server/internal/store"
)

// requireOpenApiAuth mirrors OpenApiAuthFilter: Bearer user access token
// (sk-oops-...), enabled accounts only. No JWT, no cookies, no query param.
func (s *Server) requireOpenApiAuth() gin.HandlerFunc {
	return func(c *gin.Context) {
		header := c.GetHeader("Authorization")
		if !strings.HasPrefix(header, "Bearer ") {
			c.AbortWithStatus(http.StatusUnauthorized)
			return
		}
		token := strings.TrimSpace(strings.TrimPrefix(header, "Bearer "))
		if token == "" {
			c.AbortWithStatus(http.StatusUnauthorized)
			return
		}
		user, err := s.store.FindUserByAccessToken(c.Request.Context(), token)
		if err != nil || !user.Enabled {
			c.AbortWithStatus(http.StatusUnauthorized)
			return
		}
		c.Set(principalKey, Principal{UserID: user.ID, Username: user.Username, Role: user.Role})
		c.Next()
	}
}

func hiddenFromOpenAPI(c *gin.Context) {
	c.AbortWithStatus(http.StatusMethodNotAllowed)
}

func redactEnvironment(environment store.EnvironmentFull) store.EnvironmentFull {
	if environment.KubernetesApiServer != nil {
		environment.KubernetesApiServer = &store.KubernetesApiServer{URL: environment.KubernetesApiServer.URL}
	}
	if environment.ImageRepository != nil {
		environment.ImageRepository = &store.ImageRepository{
			URL:      environment.ImageRepository.URL,
			Username: environment.ImageRepository.Username,
		}
	}
	environment.GitCredential = nil
	return environment
}

func (s *Server) openapiListEnvironments(c *gin.Context) {
	environments, err := s.store.ListEnvironmentsFull(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	redacted := make([]store.EnvironmentFull, 0, len(environments))
	for _, environment := range environments {
		redacted = append(redacted, redactEnvironment(environment))
	}
	c.JSON(http.StatusOK, ok(redacted))
}

// registerOpenAPI wires the /openapi surface: the dual-mapped application,
// deployment, pipeline, and configmap endpoints plus the discovery listing.
func (s *Server) registerOpenAPI(engine *gin.Engine) {
	openapi := engine.Group("/openapi", s.requireOpenApiAuth())

	s.registerSandbox(openapi)
	openapi.GET("/namespaces", s.listNamespaces)
	openapi.GET("/environments", s.openapiListEnvironments)
	openapi.GET("/domains", s.listDomains)

	applications := openapi.Group("/namespaces/:namespace/applications")
	applications.GET("", s.listApplications)
	applications.POST("", s.createApplication)
	applications.GET("/active-deployments", s.activeDeployments)

	application := applications.Group("/:name")
	application.GET("", s.getApplication)
	application.PUT("", s.updateApplication)
	application.DELETE("", hiddenFromOpenAPI)                   // @OpenApiHidden
	application.POST("/namespace-migration", hiddenFromOpenAPI) // @OpenApiHidden
	application.GET("/build/config", s.getBuildConfig)
	application.PUT("/build/config", s.updateBuildConfig)
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
	application.GET("/expert-config", s.getExpertConfig)
	application.PUT("/expert-config", s.updateExpertConfig)
	application.GET("/resources", s.getApplicationResources)
	application.GET("/metrics", s.getApplicationMetrics)
	application.GET("/status", s.getApplicationStatus)
	application.GET("/events", s.getApplicationEvents)
	application.GET("/current-image", s.getCurrentImage)
	application.GET("/service/cluster-domain", s.getClusterDomain)
	application.GET("/service/host-check", s.checkServiceHost)
	application.GET("/last-successful-pipeline", s.getLastSuccessfulPipeline)

	application.POST("/deployments", s.deployApplication)
	application.POST("/deployments/source-upload", s.createBuildSourceUpload)
	application.GET("/pipelines", s.listPipelines)
	application.GET("/pipelines/:id", s.getPipeline)
	application.POST("/pipelines/:id/deploy", s.manualDeployPipeline)
	application.PUT("/pipelines/:id/stop", s.stopPipeline)
	application.POST("/pipelines/:id/rollback", s.rollbackPipeline)
	application.GET("/configmaps", s.getConfigMaps)
	application.PUT("/configmaps", s.updateConfigMaps)
}
