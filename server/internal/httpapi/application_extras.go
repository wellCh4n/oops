package httpapi

import (
	"errors"
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/wellch4n/oops/server/internal/k8s"
	"github.com/wellch4n/oops/server/internal/store"
)

func (s *Server) getApplicationResources(c *gin.Context) {
	cluster, connected := s.cluster(c, c.Query("environment"))
	if !connected {
		return
	}
	resources, err := k8s.ListApplicationResources(c.Request.Context(), cluster, c.Param("namespace"), c.Param("name"))
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(resources))
}

func (s *Server) getCurrentImage(c *gin.Context) {
	cluster, connected := s.cluster(c, c.Query("environment"))
	if !connected {
		return
	}
	image, err := k8s.FindCurrentImage(c.Request.Context(), cluster, c.Param("namespace"), c.Param("name"))
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(image))
}

func (s *Server) checkServiceHost(c *gin.Context) {
	conflict, err := s.store.FindHostConflict(c.Request.Context(),
		c.Param("namespace"), c.Param("name"), c.Query("host"))
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	if conflict == nil {
		c.JSON(http.StatusOK, ok(nil))
		return
	}
	c.JSON(http.StatusOK, ok(conflict))
}

func (s *Server) getLastSuccessfulPipeline(c *gin.Context) {
	pipeline, err := s.store.FindLastSuccessfulPipeline(c.Request.Context(), c.Param("namespace"), c.Param("name"))
	if errors.Is(err, store.ErrNotFound) {
		c.JSON(http.StatusOK, ok(nil))
		return
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(gin.H{
		"deployMode":    pipeline.DeployMode,
		"publishType":   pipeline.PublishType,
		"publishConfig": pipeline.PublishConfig,
	}))
}

func (s *Server) queryPipelinesIndex(c *gin.Context) {
	var request struct {
		Namespace       string `json:"namespace"`
		ApplicationName string `json:"applicationName"`
	}
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	pipelines, err := s.store.QueryPipelines(c.Request.Context(), request.Namespace, request.ApplicationName)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(pipelines))
}

func (s *Server) queryApplicationsIndex(c *gin.Context) {
	var request struct {
		Namespace string `json:"namespace"`
		Name      string `json:"name"`
	}
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	applications, err := s.store.QueryApplications(c.Request.Context(), request.Namespace, request.Name)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	// The Java endpoint serializes the whole aggregate; this query path never
	// loads the sub-configs, so they render as nulls.
	type applicationAggregateView struct {
		store.Application
		BuildConfig   any `json:"buildConfig"`
		RuntimeSpec   any `json:"runtimeSpec"`
		ServiceConfig any `json:"serviceConfig"`
		ExpertConfig  any `json:"expertConfig"`
		Environments  any `json:"environments"`
		Collaborators any `json:"collaborators"`
	}
	views := make([]applicationAggregateView, 0, len(applications))
	for _, application := range applications {
		views = append(views, applicationAggregateView{Application: application})
	}
	c.JSON(http.StatusOK, ok(views))
}
