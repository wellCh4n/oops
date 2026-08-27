package httpapi

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/wellch4n/oops/server/internal/store"
)

type namespaceRequest struct {
	Name        string `json:"name"`
	Description string `json:"description"`
}

func (s *Server) listNamespaces(c *gin.Context) {
	namespaces, err := s.store.ListNamespaces(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(namespaces))
}

// requireAdmin mirrors @PreAuthorize("hasRole('ADMIN')").
func (s *Server) requireAdmin() gin.HandlerFunc {
	return func(c *gin.Context) {
		if principalFrom(c).Role != "ADMIN" {
			c.AbortWithStatusJSON(http.StatusForbidden, fail("Forbidden"))
			return
		}
		c.Next()
	}
}

func (s *Server) createNamespace(c *gin.Context) {
	var request namespaceRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	if !store.IsValidResourceName(request.Name) {
		c.JSON(http.StatusOK, fail("Invalid resource name"))
		return
	}
	if err := s.store.CreateNamespace(c.Request.Context(), request.Name, request.Description); err != nil {
		c.JSON(http.StatusOK, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

func (s *Server) updateNamespace(c *gin.Context) {
	var request namespaceRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	if err := s.store.UpdateNamespaceDescription(c.Request.Context(), request.Name, request.Description); err != nil {
		c.JSON(http.StatusOK, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}
