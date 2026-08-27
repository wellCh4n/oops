package httpapi

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

func (s *Server) listEnvironments(c *gin.Context) {
	environments, err := s.store.ListEnvironments(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(environments))
}

func (s *Server) listDomains(c *gin.Context) {
	domains, err := s.store.ListDomains(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(domains))
}

func (s *Server) activeDeployments(c *gin.Context) {
	deployments, err := s.store.FindActiveDeployments(c.Request.Context(), c.Param("namespace"))
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(deployments))
}
