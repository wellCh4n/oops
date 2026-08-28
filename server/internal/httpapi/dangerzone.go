package httpapi

import (
	"context"
	"log/slog"
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/wellch4n/oops/server/internal/engine"
	"github.com/wellch4n/oops/server/internal/k8s"
	"github.com/wellch4n/oops/server/internal/store"
)

// ensureOwnerOrAdmin mirrors the Java owner/admin check on the danger zone.
func (s *Server) ensureOwnerOrAdmin(ctx context.Context, application *store.Application, userID string) bool {
	if s.store.IsAdmin(ctx, userID) {
		return true
	}
	return application.Owner != nil && *application.Owner == userID
}

func (s *Server) clusterForEnvironment(environment *store.EnvironmentFull) (*k8s.Cluster, error) {
	url, token := "", ""
	if environment.KubernetesAPIServer != nil {
		if environment.KubernetesAPIServer.URL != nil {
			url = *environment.KubernetesAPIServer.URL
		}
		if environment.KubernetesAPIServer.Token != nil {
			token = *environment.KubernetesAPIServer.Token
		}
	}
	return k8s.NewCluster(url, token)
}

func (s *Server) deleteApplication(c *gin.Context) {
	ctx := c.Request.Context()
	namespace, name := c.Param("namespace"), c.Param("name")
	application, err := s.store.FindApplication(ctx, namespace, name)
	if err != nil {
		c.JSON(http.StatusOK, fail("Application not found"))
		return
	}
	if !s.ensureOwnerOrAdmin(ctx, application, principalFrom(c).UserID) {
		c.JSON(http.StatusOK, fail("Permission denied"))
		return
	}
	bindings, err := s.store.ListEnvironmentBindings(ctx, namespace, name)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	for _, binding := range bindings {
		environment, err := s.store.FindEnvironmentFullByName(ctx, binding.EnvironmentName)
		if err != nil {
			continue
		}
		cluster, err := s.clusterForEnvironment(environment)
		if err == nil {
			err = engine.DeleteWorkload(ctx, cluster, namespace, name)
		}
		if err != nil {
			slog.Error("failed to delete K8s resources", "namespace", namespace, "application", name, "environment", binding.EnvironmentName, "error", err)
			c.JSON(http.StatusOK, fail("Application deletion failed"))
			return
		}
	}
	if err := s.store.DeleteApplicationAggregate(ctx, namespace, name); err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

func (s *Server) migrateNamespace(c *gin.Context) {
	ctx := c.Request.Context()
	namespace, name := c.Param("namespace"), c.Param("name")
	var request struct {
		TargetNamespace string `json:"targetNamespace"`
	}
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Target namespace is required"))
		return
	}
	application, err := s.store.FindApplication(ctx, namespace, name)
	if err != nil {
		c.JSON(http.StatusOK, fail("Application not found"))
		return
	}
	if !s.ensureOwnerOrAdmin(ctx, application, principalFrom(c).UserID) {
		c.JSON(http.StatusOK, fail("Permission denied"))
		return
	}
	result, err := s.engine.MigrateNamespace(ctx, namespace, name, request.TargetNamespace)
	if err != nil {
		respondEngine(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(result))
}
