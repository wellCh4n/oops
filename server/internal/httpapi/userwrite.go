package httpapi

import (
	"errors"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/wellch4n/oops/server/internal/cron"
	"github.com/wellch4n/oops/server/internal/store"
)

func (s *Server) createUser(c *gin.Context) {
	var request struct {
		Username string `json:"username"`
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	if request.Username == "" {
		c.JSON(http.StatusOK, fail("Username is required"))
		return
	}
	if request.Email == "" {
		c.JSON(http.StatusOK, fail("Email is required"))
		return
	}
	err := s.store.CreateUser(c.Request.Context(), request.Username, request.Email, request.Password)
	if errors.Is(err, store.ErrDuplicateUser) {
		c.JSON(http.StatusOK, fail("Username or email already exists"))
		return
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

func (s *Server) updateUser(c *gin.Context) {
	var request struct {
		Role     string `json:"role"`
		Email    string `json:"email"`
		Password string `json:"password"`
		Enabled  *bool  `json:"enabled"`
	}
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	if err := s.store.UpdateUser(c.Request.Context(), c.Param("id"),
		request.Role, request.Email, request.Password, request.Enabled); err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

func (s *Server) deleteUser(c *gin.Context) {
	if err := s.store.DeleteUser(c.Request.Context(), c.Param("id")); err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

func (s *Server) updateMyProfile(c *gin.Context) {
	var request struct {
		Email string `json:"email"`
	}
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	if err := s.store.UpdateUserEmail(c.Request.Context(), principalFrom(c).UserID, request.Email); err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

func (s *Server) changeMyPassword(c *gin.Context) {
	var request struct {
		OldPassword string `json:"oldPassword"`
		NewPassword string `json:"newPassword"`
	}
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	if request.NewPassword == "" {
		c.JSON(http.StatusOK, fail("New password is required"))
		return
	}
	err := s.store.ChangePassword(c.Request.Context(), principalFrom(c).UserID,
		request.OldPassword, request.NewPassword)
	if errors.Is(err, store.ErrWrongPassword) {
		c.JSON(http.StatusOK, fail("Old password is incorrect"))
		return
	}
	if errors.Is(err, store.ErrNotFound) {
		c.JSON(http.StatusOK, fail("User not found"))
		return
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

func (s *Server) resetMyAccessToken(c *gin.Context) {
	token, err := s.store.ResetAccessToken(c.Request.Context(), principalFrom(c).UserID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(token))
}

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
