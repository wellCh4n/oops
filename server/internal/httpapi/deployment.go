package httpapi

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/wellch4n/oops/server/internal/engine"
)

func respondEngine(c *gin.Context, err error) {
	if engine.IsBizError(err) {
		c.JSON(http.StatusOK, fail(err.Error()))
		return
	}
	c.JSON(http.StatusInternalServerError, fail(err.Error()))
}

func (s *Server) deployApplication(c *gin.Context) {
	var request engine.DeployRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Deploy request is required"))
		return
	}
	pipelineID, err := s.engine.DeployApplication(c.Request.Context(),
		c.Param("namespace"), c.Param("name"), &request, principalFrom(c).UserID)
	if err != nil {
		respondEngine(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(pipelineID))
}

func (s *Server) manualDeployPipeline(c *gin.Context) {
	err := s.engine.ManualDeploy(c.Request.Context(), c.Param("namespace"), c.Param("name"), c.Param("id"))
	if err != nil {
		respondEngine(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

func (s *Server) stopPipeline(c *gin.Context) {
	err := s.engine.Stop(c.Request.Context(), c.Param("namespace"), c.Param("name"), c.Param("id"))
	if err != nil {
		respondEngine(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

func (s *Server) rollbackPipeline(c *gin.Context) {
	pipelineID, err := s.engine.Rollback(c.Request.Context(),
		c.Param("namespace"), c.Param("name"), c.Param("id"), principalFrom(c).UserID)
	if err != nil {
		respondEngine(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(pipelineID))
}
