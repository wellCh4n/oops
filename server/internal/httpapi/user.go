package httpapi

import (
	"errors"
	"net/http"

	"github.com/gin-gonic/gin"
	"golang.org/x/crypto/bcrypt"

	"github.com/wellch4n/oops/server/internal/store"
)

type loginRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

// login mirrors AuthController.login, including exact failure messages.
func (s *Server) login(c *gin.Context) {
	var request loginRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid username or password"))
		return
	}
	user, err := s.store.FindUserByUsernameOrEmail(c.Request.Context(), request.Username)
	if err != nil {
		if !errors.Is(err, store.ErrNotFound) {
			c.JSON(http.StatusInternalServerError, fail("Internal error"))
			return
		}
		c.JSON(http.StatusOK, fail("Invalid username or password"))
		return
	}
	if bcrypt.CompareHashAndPassword([]byte(user.Password), []byte(request.Password)) != nil {
		c.JSON(http.StatusOK, fail("Invalid username or password"))
		return
	}
	if !user.Enabled {
		c.JSON(http.StatusOK, fail("Account is disabled"))
		return
	}
	token, err := s.signJWT(user.ID, user.Username, user.Role)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail("Internal error"))
		return
	}
	c.JSON(http.StatusOK, ok(gin.H{
		"token":    token,
		"userId":   user.ID,
		"username": user.Username,
		"role":     user.Role,
	}))
}

func (s *Server) listUsers(c *gin.Context) {
	users, err := s.store.ListUsers(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	for i := range users {
		users[i].AccessToken = nil
	}
	c.JSON(http.StatusOK, ok(users))
}

func (s *Server) listUsersPage(c *gin.Context) {
	keyword := c.Query("keyword")
	page := queryInt(c, "page", 1)
	size := queryInt(c, "size", 10)
	total, users, err := s.store.PageUsers(c.Request.Context(), keyword, page, size)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	// Never expose other users' OpenAPI access tokens in a listing.
	for i := range users {
		users[i].AccessToken = nil
	}
	c.JSON(http.StatusOK, ok(NewPage(total, users, size)))
}

func (s *Server) me(c *gin.Context) {
	principal := principalFrom(c)
	user, err := s.store.FindUserByID(c.Request.Context(), principal.UserID)
	if err != nil {
		c.JSON(http.StatusOK, fail("User not found"))
		return
	}
	c.JSON(http.StatusOK, ok(user))
}
