// GET /api/cron/next previews upcoming fire times for the scheduled-restart UI.
package httpapi

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/wellch4n/oops/server/internal/cron"
)

// cronNext mirrors CronController: preview upcoming fire times, local time,
// "yyyy-MM-dd HH:mm".
func (s *Server) cronNext(c *gin.Context) {
	expression := c.Query("expression")
	if !cron.IsValid(expression) {
		c.JSON(http.StatusOK, fail("Invalid cron expression"))
		return
	}
	count := min(max(queryInt(c, "count", 1), 1), 5)
	runs, err := cron.NextRuns(expression, count, time.Now())
	if err != nil {
		c.JSON(http.StatusOK, fail("Invalid cron expression"))
		return
	}
	formatted := make([]string, 0, len(runs))
	for _, run := range runs {
		formatted = append(formatted, run.Format("2006-01-02 15:04"))
	}
	c.JSON(http.StatusOK, ok(formatted))
}
