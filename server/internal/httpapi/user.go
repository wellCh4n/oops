// User handlers: login, roster, paging, admin writes and self-service.
package httpapi

import (
	"errors"
	"github.com/gin-gonic/gin"
	"github.com/wellch4n/oops/server/internal/store"
	"golang.org/x/crypto/bcrypt"
	"net/http"
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
