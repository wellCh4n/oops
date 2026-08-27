// Package httpapi serves the /api surface with wire-compatible responses, so
// the existing Next.js frontend works unchanged against this backend.
package httpapi

import (
	"github.com/gin-gonic/gin"

	"github.com/wellch4n/oops/go-backend/internal/config"
	"github.com/wellch4n/oops/go-backend/internal/crypto"
	"github.com/wellch4n/oops/go-backend/internal/store"
)

type Server struct {
	cfg   *config.Config
	store *store.Store
	codec *crypto.Codec
}

func NewServer(cfg *config.Config, st *store.Store) *Server {
	return &Server{cfg: cfg, store: st, codec: crypto.NewCodec(cfg.Oops.Crypto.SecretKey)}
}

func (s *Server) Handler() *gin.Engine {
	engine := gin.New()
	engine.Use(gin.Recovery(), s.cors())

	api := engine.Group("/api")
	api.GET("/health", s.health)
	api.POST("/auth/login", s.login)
	api.GET("/features", s.features)

	authed := api.Group("", s.requireAuth())
	authed.GET("/users/me", s.me)
	authed.GET("/users/page", s.listUsersPage)
	authed.GET("/domains", s.listDomains)
	authed.GET("/namespaces", s.listNamespaces)
	authed.GET("/environments", s.listEnvironments)
	authed.GET("/nodes", s.listNodes)
	authed.GET("/namespaces/:namespace/applications", s.listApplications)
	authed.GET("/namespaces/:namespace/applications/active-deployments", s.activeDeployments)
	authed.GET("/search/applications", s.searchApplications)

	admin := authed.Group("", s.requireAdmin())
	admin.POST("/namespaces", s.createNamespace)
	admin.PUT("/namespaces", s.updateNamespace)

	return engine
}

// cors matches the Spring CORS setup so the dev frontend on :3000 can call us.
func (s *Server) cors() gin.HandlerFunc {
	return func(c *gin.Context) {
		if origin := c.GetHeader("Origin"); origin != "" {
			c.Header("Access-Control-Allow-Origin", origin)
			c.Header("Access-Control-Allow-Headers", "Authorization, Content-Type")
			c.Header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		}
		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(204)
			return
		}
		c.Next()
	}
}

func (s *Server) health(c *gin.Context) {
	c.JSON(200, ok("UP"))
}

func (s *Server) features(c *gin.Context) {
	c.JSON(200, ok(gin.H{
		"feishu":        s.cfg.Oops.Feishu.Enabled,
		"ide":           s.cfg.Oops.IDE.Enabled,
		"ideHost":       s.cfg.Oops.IDE.Domain,
		"ideHttps":      s.cfg.Oops.IDE.HTTPS,
		"objectStorage": s.cfg.Oops.ObjectStorage.Enabled,
	}))
}
