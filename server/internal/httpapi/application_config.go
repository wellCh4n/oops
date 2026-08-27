package httpapi

import (
	"errors"
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/wellch4n/oops/server/internal/store"
)

// The config GET endpoints all return Result.success(null) when no row exists
// yet — Java returns the mapped null — so the frontend can render empty forms.
func respondConfig[T any](c *gin.Context, view *T, err error) {
	if errors.Is(err, store.ErrNotFound) {
		c.JSON(http.StatusOK, ok(nil))
		return
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(view))
}

func (s *Server) getApplication(c *gin.Context) {
	namespace, name := c.Param("namespace"), c.Param("name")
	application, err := s.store.FindApplication(c.Request.Context(), namespace, name)
	if errors.Is(err, store.ErrNotFound) {
		c.JSON(http.StatusOK, ok(nil))
		return
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	views, err := s.toApplicationViews(c.Request.Context(), namespace, []store.Application{*application}, true)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(views[0]))
}

func (s *Server) getBuildConfig(c *gin.Context) {
	view, err := s.store.FindBuildConfig(c.Request.Context(), c.Param("namespace"), c.Param("name"))
	respondConfig(c, view, err)
}

func (s *Server) getBuildEnvironmentConfigs(c *gin.Context) {
	configs, err := s.store.FindBuildEnvironmentConfigs(c.Request.Context(), c.Param("namespace"), c.Param("name"))
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(configs))
}

func (s *Server) getRuntimeSpec(c *gin.Context) {
	namespace, name := c.Param("namespace"), c.Param("name")
	view, err := s.store.FindRuntimeSpec(c.Request.Context(), namespace, name)
	if errors.Is(err, store.ErrNotFound) {
		c.JSON(http.StatusOK, ok(store.DefaultRuntimeSpec(namespace, name)))
		return
	}
	respondConfig(c, view, err)
}

func (s *Server) getRuntimeSpecEnvironmentConfigs(c *gin.Context) {
	view, err := s.store.FindRuntimeSpec(c.Request.Context(), c.Param("namespace"), c.Param("name"))
	if errors.Is(err, store.ErrNotFound) {
		c.JSON(http.StatusOK, ok([]store.RuntimeEnvironmentConfig{}))
		return
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	configs := view.EnvironmentConfigs
	if configs == nil {
		configs = []store.RuntimeEnvironmentConfig{}
	}
	c.JSON(http.StatusOK, ok(configs))
}

func (s *Server) getServiceConfig(c *gin.Context) {
	view, err := s.store.FindServiceConfig(c.Request.Context(), c.Param("namespace"), c.Param("name"))
	respondConfig(c, view, err)
}

func (s *Server) getExpertConfig(c *gin.Context) {
	namespace, name := c.Param("namespace"), c.Param("name")
	view, err := s.store.FindExpertConfig(c.Request.Context(), namespace, name)
	if errors.Is(err, store.ErrNotFound) {
		// defaultExpertConfig: an empty config bound to the application.
		c.JSON(http.StatusOK, ok(store.ExpertConfigView{
			Namespace:          namespace,
			ApplicationName:    name,
			EnvironmentConfigs: []store.ExpertEnvironmentConfig{},
		}))
		return
	}
	respondConfig(c, view, err)
}

func (s *Server) getEnvironmentBindings(c *gin.Context) {
	bindings, err := s.store.ListEnvironmentBindings(c.Request.Context(), c.Param("namespace"), c.Param("name"))
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(bindings))
}

func (s *Server) listPipelines(c *gin.Context) {
	namespace, name := c.Param("namespace"), c.Param("name")
	environment := c.Query("environment")
	page := queryInt(c, "page", 1)
	size := queryInt(c, "size", 10)
	total, pipelines, err := s.store.PagePipelines(c.Request.Context(), namespace, name, environment, page, size)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	s.fillOperatorNames(c, pipelines)
	c.JSON(http.StatusOK, ok(NewPage(total, pipelines, size)))
}

func (s *Server) getPipeline(c *gin.Context) {
	view, err := s.store.FindPipeline(c.Request.Context(), c.Param("namespace"), c.Param("name"), c.Param("id"))
	if errors.Is(err, store.ErrNotFound) {
		c.JSON(http.StatusOK, ok(nil))
		return
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	single := []store.PipelineView{*view}
	s.fillOperatorNames(c, single)
	c.JSON(http.StatusOK, ok(single[0]))
}

func (s *Server) fillOperatorNames(c *gin.Context, pipelines []store.PipelineView) {
	idSet := map[string]struct{}{}
	for _, pipeline := range pipelines {
		if pipeline.OperatorID != nil && *pipeline.OperatorID != "" {
			idSet[*pipeline.OperatorID] = struct{}{}
		}
	}
	ids := make([]string, 0, len(idSet))
	for id := range idSet {
		ids = append(ids, id)
	}
	usernames, err := s.store.UsernamesByIDs(c.Request.Context(), ids)
	if err != nil {
		return // names are cosmetic; the listing still works without them
	}
	for i := range pipelines {
		if pipelines[i].OperatorID != nil {
			if name, found := usernames[*pipelines[i].OperatorID]; found {
				pipelines[i].OperatorName = &name
			}
		}
	}
}
