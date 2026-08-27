package httpapi

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/wellch4n/oops/server/internal/k8s"
)

func (s *Server) getConfigMaps(c *gin.Context) {
	cluster, connected := s.cluster(c, c.Query("environment"))
	if !connected {
		return
	}
	items, err := k8s.GetConfigMaps(c.Request.Context(), cluster, c.Param("namespace"), c.Param("name"))
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(items))
}

func (s *Server) updateConfigMaps(c *gin.Context) {
	var commands []k8s.UpdateConfigMapCommand
	if err := c.ShouldBindJSON(&commands); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	cluster, connected := s.cluster(c, c.Query("environment"))
	if !connected {
		return
	}
	if err := k8s.UpdateConfigMaps(c.Request.Context(), cluster, c.Param("namespace"), c.Param("name"), commands); err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}
