package httpapi

import (
	"context"
	"errors"
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/wellch4n/oops/server/internal/k8s"
	"github.com/wellch4n/oops/server/internal/store"
)

func value(pointer *string) string {
	if pointer == nil {
		return ""
	}
	return *pointer
}

// syncEnvironmentSecrets mirrors syncDockerHubSecret + syncGitCredentialSecret,
// run after every environment create/update.
func (s *Server) syncEnvironmentSecrets(ctx context.Context, environment *store.EnvironmentFull) error {
	if environment.KubernetesApiServer == nil || value(environment.WorkNamespace) == "" {
		return nil
	}
	cluster, err := k8s.NewCluster(value(environment.KubernetesApiServer.URL), value(environment.KubernetesApiServer.Token))
	if err != nil {
		return err
	}
	if environment.ImageRepository != nil {
		if err := k8s.SyncImagePullSecret(ctx, cluster, *environment.WorkNamespace,
			value(environment.ImageRepository.URL), value(environment.ImageRepository.Username),
			value(environment.ImageRepository.Password)); err != nil {
			return err
		}
	}
	var username, password, privateKey string
	if environment.GitCredential != nil {
		username = value(environment.GitCredential.Username)
		password = value(environment.GitCredential.Password)
		privateKey = value(environment.GitCredential.PrivateKey)
	}
	return k8s.SyncGitCredentialSecret(ctx, cluster, *environment.WorkNamespace, username, password, privateKey)
}

func (s *Server) getEnvironment(c *gin.Context) {
	environment, err := s.store.FindEnvironmentByID(c.Request.Context(), c.Param("id"))
	if errors.Is(err, store.ErrNotFound) {
		c.JSON(http.StatusOK, ok(nil))
		return
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(environment))
}

func (s *Server) createEnvironment(c *gin.Context) {
	var request store.EnvironmentFull
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	id, err := s.store.CreateEnvironment(c.Request.Context(), &request)
	if err != nil {
		respondBiz(c, err)
		return
	}
	if err := s.syncEnvironmentSecrets(c.Request.Context(), &request); err != nil {
		c.JSON(http.StatusOK, fail("Failed to sync secrets to namespace "+value(request.WorkNamespace)+": "+err.Error()))
		return
	}
	request.ID = id
	c.JSON(http.StatusOK, ok(request))
}

func (s *Server) updateEnvironmentCluster(c *gin.Context) {
	var request store.EnvironmentFull
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	id := c.Param("id")
	if err := s.store.UpdateEnvironmentClusterConfig(c.Request.Context(), id,
		request.KubernetesApiServer, request.WorkNamespace, request.BuildStorageClass); err != nil {
		respondBiz(c, err)
		return
	}
	updated, err := s.store.FindEnvironmentByID(c.Request.Context(), id)
	if err == nil {
		if err := s.syncEnvironmentSecrets(c.Request.Context(), updated); err != nil {
			c.JSON(http.StatusOK, fail("Failed to sync secrets: "+err.Error()))
			return
		}
	}
	c.JSON(http.StatusOK, ok(true))
}

func (s *Server) updateEnvironmentCredentials(c *gin.Context) {
	var request store.EnvironmentFull
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	id := c.Param("id")
	if err := s.store.UpdateEnvironmentCredentialConfig(c.Request.Context(), id,
		request.ImageRepository, request.GitCredential); err != nil {
		respondBiz(c, err)
		return
	}
	updated, err := s.store.FindEnvironmentByID(c.Request.Context(), id)
	if err == nil {
		if err := s.syncEnvironmentSecrets(c.Request.Context(), updated); err != nil {
			c.JSON(http.StatusOK, fail("Failed to sync secrets: "+err.Error()))
			return
		}
	}
	c.JSON(http.StatusOK, ok(true))
}

func (s *Server) deleteEnvironment(c *gin.Context) {
	if err := s.store.DeleteEnvironment(c.Request.Context(), c.Param("id")); err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

type kubernetesValidationRequest struct {
	KubernetesApiServer *store.KubernetesApiServer `json:"kubernetesApiServer"`
	WorkNamespace       string                     `json:"workNamespace"`
}

func (s *Server) validateKubernetes(c *gin.Context) {
	var request kubernetesValidationRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	result := func(success bool, status, message string) gin.H {
		return gin.H{"success": success, "status": status, "message": message}
	}
	if request.KubernetesApiServer == nil ||
		!k8s.CanConnect(c.Request.Context(), value(request.KubernetesApiServer.URL), value(request.KubernetesApiServer.Token)) {
		c.JSON(http.StatusOK, ok(result(false, "CONNECTION_FAILED", "Unable to connect to Kubernetes API Server")))
		return
	}
	if request.WorkNamespace == "" {
		c.JSON(http.StatusOK, ok(result(true, "VALID", "Connection successful")))
		return
	}
	cluster, err := k8s.NewCluster(value(request.KubernetesApiServer.URL), value(request.KubernetesApiServer.Token))
	if err != nil {
		c.JSON(http.StatusOK, ok(result(false, "ERROR", "Validation failed: "+err.Error())))
		return
	}
	exists, err := k8s.NamespaceExists(c.Request.Context(), cluster, request.WorkNamespace)
	if err != nil {
		c.JSON(http.StatusOK, ok(result(false, "ERROR", "Validation failed: "+err.Error())))
		return
	}
	if !exists {
		c.JSON(http.StatusOK, ok(result(false, "NAMESPACE_MISSING", "Work namespace does not exist")))
		return
	}
	c.JSON(http.StatusOK, ok(result(true, "VALID", "Validation passed")))
}

func (s *Server) createKubernetesNamespace(c *gin.Context) {
	var request kubernetesValidationRequest
	if err := c.ShouldBindJSON(&request); err != nil || request.KubernetesApiServer == nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	cluster, err := k8s.NewCluster(value(request.KubernetesApiServer.URL), value(request.KubernetesApiServer.Token))
	if err != nil {
		c.JSON(http.StatusOK, fail("Failed to create work namespace: "+err.Error()))
		return
	}
	if err := k8s.CreateNamespace(c.Request.Context(), cluster, request.WorkNamespace); err != nil {
		c.JSON(http.StatusOK, fail("Failed to create work namespace: "+err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

func (s *Server) validateImageRepository(c *gin.Context) {
	var request store.ImageRepository
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	valid := k8s.ValidateImageRepository(value(request.URL), value(request.Username), value(request.Password))
	c.JSON(http.StatusOK, ok(valid))
}
