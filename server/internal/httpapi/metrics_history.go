package httpapi

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/wellch4n/oops/server/internal/k8s"
)

func (s *Server) getApplicationMetricsHistory(c *gin.Context) {
	cluster, connected := s.cluster(c, c.Query("environment"))
	if !connected {
		return
	}
	history := s.cfg.Oops.Metrics.History
	backend := k8s.MetricsBackend{
		Namespace:   history.Backend.Namespace,
		ServiceName: history.Backend.ServiceName,
		Port:        history.Backend.Port,
	}
	rangeSpec := c.DefaultQuery("range", "1h")
	aggregation := c.DefaultQuery("agg", "avg")
	result, err := k8s.QueryPodMetricHistory(c.Request.Context(), cluster, backend,
		c.Param("namespace"), c.Param("name"), rangeSpec, aggregation,
		history.IntervalSeconds, history.MaxRangeHours)
	if err != nil {
		if k8s.IsMonitoringError(err) {
			c.JSON(http.StatusOK, fail(err.Error()))
			return
		}
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(result))
}
