package httpapi

import (
	"encoding/json"
	"fmt"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/wellch4n/oops/server/internal/k8s"
)

// cluster resolves an environment name to a connected cluster, decrypting the
// stored token, like the environmentRepository + KubernetesClients pair.
func (s *Server) cluster(c *gin.Context, environmentName string) (*k8s.Cluster, bool) {
	if environmentName == "" {
		c.JSON(http.StatusOK, fail("environment is required"))
		return nil, false
	}
	credentials, err := s.store.FindEnvironmentCredentials(c.Request.Context(), environmentName)
	if err != nil {
		c.JSON(http.StatusOK, fail("Environment not found: "+environmentName))
		return nil, false
	}
	token, err := s.codec.Decrypt(credentials.Token)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail("Failed to decrypt environment token"))
		return nil, false
	}
	cluster, err := k8s.NewCluster(credentials.APIServerURL, token)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return nil, false
	}
	return cluster, true
}

func (s *Server) getApplicationStatus(c *gin.Context) {
	cluster, connected := s.cluster(c, c.Query("environment"))
	if !connected {
		return
	}
	views, err := k8s.ListPodStatuses(c.Request.Context(), cluster.Clientset, c.Param("namespace"), c.Param("name"))
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(views))
}

func (s *Server) getApplicationEvents(c *gin.Context) {
	cluster, connected := s.cluster(c, c.Query("environment"))
	if !connected {
		return
	}
	var since *time.Time
	if raw := c.Query("since"); raw != "" {
		if parsed, err := time.Parse(time.RFC3339, raw); err == nil {
			since = &parsed
		}
	}
	limit := queryInt(c, "limit", 50)
	views, err := k8s.ListApplicationEvents(c.Request.Context(), cluster.Clientset,
		c.Param("namespace"), c.Param("name"), since, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(views))
}

func (s *Server) getApplicationMetrics(c *gin.Context) {
	cluster, connected := s.cluster(c, c.Query("environment"))
	if !connected {
		return
	}
	snapshots, err := k8s.ListPodMetrics(c.Request.Context(), cluster.Clientset, cluster.Config,
		c.Param("namespace"), c.Param("name"))
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(snapshots))
}

func (s *Server) getClusterDomain(c *gin.Context) {
	namespace, name := c.Param("namespace"), c.Param("name")
	environmentName := c.Query("environment")
	cluster, connected := s.cluster(c, environmentName)
	if !connected {
		return
	}
	internalDomain, err := k8s.FindInternalServiceDomain(c.Request.Context(), cluster.Clientset, namespace, name)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	// External domains come from the stored service config hosts for this env.
	externalDomains := []string{}
	serviceConfig, err := s.store.FindServiceConfig(c.Request.Context(), namespace, name)
	if err == nil && serviceConfig != nil {
		for _, environmentConfig := range serviceConfig.EnvironmentConfigs {
			if environmentConfig.Environment == nil || *environmentConfig.Environment != environmentName {
				continue
			}
			if environmentConfig.Host == nil || *environmentConfig.Host == "" {
				continue
			}
			scheme := "http"
			if environmentConfig.HTTPS != nil && *environmentConfig.HTTPS {
				scheme = "https"
			}
			externalDomains = append(externalDomains, scheme+"://"+*environmentConfig.Host)
		}
	}
	c.JSON(http.StatusOK, ok(gin.H{
		"internalDomain":  internalDomain,
		"externalDomains": externalDomains,
	}))
}

func (s *Server) restartApplicationPod(c *gin.Context) {
	cluster, connected := s.cluster(c, c.Query("environment"))
	if !connected {
		return
	}
	if err := k8s.RestartPod(c.Request.Context(), cluster.Clientset, c.Param("namespace"), c.Param("pod")); err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

// watchApplicationStatus streams SSE "status" events carrying the full pod
// snapshot, mirroring the Java SseEmitter behaviour.
func (s *Server) watchApplicationStatus(c *gin.Context) {
	cluster, connected := s.cluster(c, c.Query("environment"))
	if !connected {
		return
	}
	c.Writer.Header().Set("Content-Type", "text/event-stream")
	c.Writer.Header().Set("Cache-Control", "no-cache")
	c.Writer.Header().Set("X-Accel-Buffering", "no")
	c.Writer.Flush()

	err := k8s.WatchPodStatuses(c.Request.Context(), cluster.Clientset,
		c.Param("namespace"), c.Param("name"), func(snapshot []k8s.PodStatusView) error {
			payload, err := json.Marshal(snapshot)
			if err != nil {
				return err
			}
			if _, err := fmt.Fprintf(c.Writer, "event:status\ndata:%s\n\n", payload); err != nil {
				return err
			}
			c.Writer.Flush()
			return nil
		})
	if err != nil {
		// The stream is already open; just end it.
		return
	}
}
