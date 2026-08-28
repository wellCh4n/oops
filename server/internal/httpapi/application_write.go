package httpapi

import (
	"errors"
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/store"
)

type applicationProfileRequest struct {
	Name          string   `json:"name"`
	Description   string   `json:"description"`
	Icon          string   `json:"icon"`
	Owner         string   `json:"owner"`
	Collaborators []string `json:"collaborators"`
}

func (s *Server) createApplication(c *gin.Context) {
	var request applicationProfileRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	if !domain.IsValidResourceName(request.Name) {
		c.JSON(http.StatusOK, fail("Invalid resource name"))
		return
	}
	principal := principalFrom(c)
	id, err := s.store.CreateApplication(c.Request.Context(), c.Param("namespace"),
		request.Name, request.Description, request.Icon, principal.UserID)
	if errors.Is(err, store.ErrDuplicateName) {
		c.JSON(http.StatusOK, fail("Application name already exists"))
		return
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(id))
}

func (s *Server) updateApplication(c *gin.Context) {
	var request applicationProfileRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	err := s.store.UpdateApplicationProfile(c.Request.Context(), c.Param("namespace"), c.Param("name"),
		request.Description, request.Owner, request.Icon, request.Collaborators)
	if errors.Is(err, store.ErrNotFound) {
		c.JSON(http.StatusOK, fail("Application not found"))
		return
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

type buildConfigRequest struct {
	SourceType         *string                        `json:"sourceType"`
	Repository         *string                        `json:"repository"`
	DockerFileConfig   *store.DockerFileConfig        `json:"dockerFileConfig"`
	BuildImage         *string                        `json:"buildImage"`
	EnvironmentConfigs []store.BuildEnvironmentConfig `json:"environmentConfigs"`
}

func (s *Server) updateBuildConfig(c *gin.Context) {
	var request buildConfigRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	err := s.store.SaveBuildConfig(c.Request.Context(), c.Param("namespace"), c.Param("name"),
		request.SourceType, request.Repository, request.DockerFileConfig, request.BuildImage,
		request.EnvironmentConfigs)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

func (s *Server) updateBuildEnvironmentConfigs(c *gin.Context) {
	var configs []store.BuildEnvironmentConfig
	if err := c.ShouldBindJSON(&configs); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	if err := s.store.SaveBuildEnvironmentConfigs(c.Request.Context(), c.Param("namespace"), c.Param("name"), configs); err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

type runtimeSpecRequest struct {
	EnvironmentConfigs []store.RuntimeEnvironmentConfig `json:"environmentConfigs"`
	HealthCheck        *store.HealthCheck               `json:"healthCheck"`
}

func (s *Server) updateRuntimeSpec(c *gin.Context) {
	var request runtimeSpecRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	err := s.store.SaveRuntimeSpec(c.Request.Context(), c.Param("namespace"), c.Param("name"),
		request.EnvironmentConfigs, request.HealthCheck)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

func (s *Server) updateRuntimeSpecEnvironmentConfigs(c *gin.Context) {
	var configs []store.RuntimeEnvironmentConfig
	if err := c.ShouldBindJSON(&configs); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	if err := s.store.SaveRuntimeSpecEnvironmentConfigs(c.Request.Context(), c.Param("namespace"), c.Param("name"), configs); err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

type serviceConfigRequest struct {
	Port               *int                                  `json:"port"`
	InternalPorts      []int                                 `json:"internalPorts"`
	EnvironmentConfigs []store.ServiceEnvironmentConfigInput `json:"environmentConfigs"`
}

func (s *Server) updateServiceConfig(c *gin.Context) {
	var request serviceConfigRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	err := s.store.SaveServiceConfig(c.Request.Context(), c.Param("namespace"), c.Param("name"),
		request.Port, request.InternalPorts, request.EnvironmentConfigs)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

func (s *Server) updateExpertConfig(c *gin.Context) {
	var request struct {
		EnvironmentConfigs []store.ExpertEnvironmentConfig `json:"environmentConfigs"`
	}
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	if err := s.store.SaveExpertConfig(c.Request.Context(), c.Param("namespace"), c.Param("name"), request.EnvironmentConfigs); err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

func (s *Server) updateEnvironmentBindings(c *gin.Context) {
	var bindings []struct {
		EnvironmentName string `json:"environmentName"`
	}
	if err := c.ShouldBindJSON(&bindings); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	names := make([]string, 0, len(bindings))
	for _, binding := range bindings {
		names = append(names, binding.EnvironmentName)
	}
	if err := s.store.ReplaceEnvironmentBindings(c.Request.Context(), c.Param("namespace"), c.Param("name"), names); err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}
