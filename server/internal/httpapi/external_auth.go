package httpapi

import (
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/wellch4n/oops/server/internal/feishu"
	"github.com/wellch4n/oops/server/internal/store"
)

func (s *Server) feishuClient() *feishu.Client {
	return feishu.NewClient(s.cfg.Oops.Feishu.AppID, s.cfg.Oops.Feishu.AppSecret)
}

func (s *Server) externalProviders(c *gin.Context) {
	providers := []string{}
	if s.cfg.Oops.Feishu.Enabled {
		providers = append(providers, "FEISHU")
	}
	c.JSON(http.StatusOK, ok(providers))
}

func (s *Server) requireFeishuProvider(c *gin.Context) bool {
	provider := strings.ToUpper(c.Param("provider"))
	if provider != "FEISHU" || !s.cfg.Oops.Feishu.Enabled {
		c.JSON(http.StatusOK, fail("Provider not enabled: "+c.Param("provider")))
		return false
	}
	return true
}

func (s *Server) externalRedirect(c *gin.Context) {
	if !s.requireFeishuProvider(c) {
		return
	}
	c.JSON(http.StatusOK, ok(s.feishuClient().LoginURL(s.cfg.Oops.Feishu.RedirectURI)))
}

var unsafeEmailCharacters = regexp.MustCompile(`[^A-Za-z0-9._-]`)

func (s *Server) externalCallback(c *gin.Context) {
	if !s.requireFeishuProvider(c) {
		return
	}
	code := c.Query("code")
	if code == "" {
		c.JSON(http.StatusOK, fail("code is required"))
		return
	}
	ctx := c.Request.Context()
	info, err := s.feishuClient().Authenticate(ctx, code)
	if err != nil {
		c.JSON(http.StatusOK, fail(err.Error()))
		return
	}
	if info.UserID == "" {
		c.JSON(http.StatusOK, fail("Feishu user id is missing"))
		return
	}
	email := info.EnterpriseEmail
	if email == "" {
		email = info.Email
	}
	if email == "" {
		email = "feishu_" + unsafeEmailCharacters.ReplaceAllString(info.UserID, "_") + "@feishu.invalid"
	}

	findOrCreateUser := func() (*store.User, error) {
		if user, err := s.store.FindUserByEmail(ctx, email); err == nil {
			return user, nil
		}
		username := info.Name
		if strings.TrimSpace(username) == "" {
			username = fmt.Sprintf("feishu_%d", time.Now().UnixMilli())
		}
		return s.store.CreateExternalUser(ctx, username, email)
	}

	var user *store.User
	if account, err := s.store.FindExternalAccountByProviderUser(ctx, "FEISHU", info.UserID); err == nil {
		user, err = s.store.FindUserByID(ctx, account.UserID)
		if err != nil {
			if user, err = findOrCreateUser(); err != nil {
				c.JSON(http.StatusInternalServerError, fail(err.Error()))
				return
			}
			_ = s.store.SaveExternalAccount(ctx, "FEISHU", info.UserID, user.ID, email)
		}
	} else if errors.Is(err, store.ErrNotFound) {
		if user, err = findOrCreateUser(); err != nil {
			c.JSON(http.StatusInternalServerError, fail(err.Error()))
			return
		}
		_ = s.store.SaveExternalAccount(ctx, "FEISHU", info.UserID, user.ID, email)
	} else {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}

	if !user.Enabled {
		c.JSON(http.StatusOK, fail("Account is disabled"))
		return
	}
	token, err := s.signJWT(user.ID, user.Username, user.Role)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(token))
}
