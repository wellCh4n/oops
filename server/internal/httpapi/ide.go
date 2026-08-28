package httpapi

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"

	"github.com/wellch4n/oops/server/internal/k8s"
)

func (s *Server) ideSettings() k8s.IDESettings {
	return k8s.IDESettings{
		Domain:       s.cfg.Oops.IDE.Domain,
		HTTPS:        s.cfg.Oops.IDE.HTTPS,
		Image:        s.cfg.Oops.IDE.Image,
		Middlewares:  s.cfg.IDEMiddlewares(),
		CloneImage:   s.cfg.Oops.Pipeline.Image.Clone,
		CertResolver: s.cfg.Oops.Ingress.CertResolver,
	}
}

func (s *Server) ideWorkTarget(c *gin.Context) (*k8s.Cluster, string, bool) {
	cluster, _, workNamespace, err := s.sandboxEnvironment(c.Request.Context(), c.Query("env"))
	if err != nil {
		c.JSON(http.StatusOK, fail(err.Error()))
		return nil, "", false
	}
	return cluster, workNamespace, true
}

func (s *Server) listIdes(c *gin.Context) {
	cluster, workNamespace, resolved := s.ideWorkTarget(c)
	if !resolved {
		return
	}
	views, err := k8s.ListIDEs(c.Request.Context(), cluster, workNamespace, c.Param("name"), s.ideSettings())
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(views))
}

func (s *Server) getDefaultIDEConfig(c *gin.Context) {
	cluster, workNamespace, resolved := s.ideWorkTarget(c)
	if !resolved {
		return
	}
	config, err := k8s.GetDefaultIDEConfig(c.Request.Context(), cluster, workNamespace)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(config))
}

func (s *Server) createIDE(c *gin.Context) {
	var request k8s.CreateIDERequest
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	namespace, applicationName := c.Param("namespace"), c.Param("name")
	buildConfig, err := s.store.FindBuildConfig(c.Request.Context(), namespace, applicationName)
	if err == nil && buildConfig.SourceType != nil && *buildConfig.SourceType == "ZIP" {
		c.JSON(http.StatusOK, fail("IDE is not supported for ZIP source applications"))
		return
	}
	repository := ""
	if err == nil && buildConfig.Repository != nil {
		repository = *buildConfig.Repository
	}
	cluster, workNamespace, resolved := s.ideWorkTarget(c)
	if !resolved {
		return
	}
	ideID, err := k8s.CreateIDE(c.Request.Context(), cluster, workNamespace, namespace, applicationName,
		repository, s.ideSettings(), &request)
	if err != nil {
		c.JSON(http.StatusOK, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(ideID))
}

func (s *Server) deleteIDE(c *gin.Context) {
	cluster, workNamespace, resolved := s.ideWorkTarget(c)
	if !resolved {
		return
	}
	name := strings.TrimSpace(c.Param("ide"))
	if err := k8s.DeleteIDE(c.Request.Context(), cluster, workNamespace, name); err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(nil))
}
