package httpapi

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/wellch4n/oops/go-backend/internal/k8s"
)

func (s *Server) listNodes(c *gin.Context) {
	environmentName := c.Query("env")
	if environmentName == "" {
		c.JSON(http.StatusOK, fail("env is required"))
		return
	}
	credentials, err := s.store.FindEnvironmentCredentials(c.Request.Context(), environmentName)
	if err != nil {
		c.JSON(http.StatusOK, fail("Environment not found"))
		return
	}
	token, err := s.codec.Decrypt(credentials.Token)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail("Failed to decrypt environment token"))
		return
	}
	client, err := k8s.NewClient(credentials.APIServerURL, token)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	nodes, err := k8s.ListNodes(c.Request.Context(), client)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(nodes))
}
