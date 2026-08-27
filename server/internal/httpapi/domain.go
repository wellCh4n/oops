package httpapi

import (
	"errors"
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/wellch4n/oops/server/internal/store"
)

// respondBiz maps store.BizError to Result.failure like GlobalExceptionHandler.
func respondBiz(c *gin.Context, err error) {
	var bizError *store.BizError
	if errors.As(err, &bizError) {
		c.JSON(http.StatusOK, fail(bizError.Message))
		return
	}
	c.JSON(http.StatusInternalServerError, fail(err.Error()))
}

func (s *Server) getDomain(c *gin.Context) {
	view, err := s.store.FindDomain(c.Request.Context(), c.Param("id"))
	if err != nil {
		respondBiz(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(view))
}

func (s *Server) createDomain(c *gin.Context) {
	var request store.UpsertDomainCommand
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	view, err := s.store.CreateDomain(c.Request.Context(), request)
	if err != nil {
		respondBiz(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(view))
}

func (s *Server) updateDomain(c *gin.Context) {
	var request store.UpsertDomainCommand
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	view, err := s.store.UpdateDomain(c.Request.Context(), c.Param("id"), request)
	if err != nil {
		respondBiz(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(view))
}

func (s *Server) deleteDomain(c *gin.Context) {
	if err := s.store.DeleteDomain(c.Request.Context(), c.Param("id")); err != nil {
		respondBiz(c, err)
		return
	}
	c.JSON(http.StatusOK, ok(true))
}
