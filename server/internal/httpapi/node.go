package httpapi

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/wellch4n/oops/server/internal/k8s"
)

func (s *Server) listNodes(c *gin.Context) {
	cluster, connected := s.cluster(c, c.Query("environment"))
	if !connected {
		return
	}
	nodes, err := k8s.ListNodes(c.Request.Context(), cluster.Clientset)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(nodes))
}

// setNodeSchedulable mirrors NodeController.setSchedulable: cordon or uncordon
// a node (admin only).
func (s *Server) setNodeSchedulable(c *gin.Context) {
	cluster, connected := s.cluster(c, c.Query("environment"))
	if !connected {
		return
	}
	schedulable := c.Query("schedulable") == "true"
	err := k8s.SetNodeSchedulable(c.Request.Context(), cluster.Clientset, c.Param("name"), schedulable)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}
