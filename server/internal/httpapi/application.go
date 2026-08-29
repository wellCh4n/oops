// Application handlers: listing/search and profile/collaborator writes.
package httpapi

import (
	"context"
	"errors"
	"github.com/gin-gonic/gin"
	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/store"
	"net/http"
	"strconv"
)

// applicationView mirrors ApplicationDto field-for-field.
type applicationView struct {
	ID                string               `json:"id"`
	CreatedTime       *store.LocalDateTime `json:"createdTime"`
	Name              string               `json:"name"`
	Description       *string              `json:"description"`
	Icon              *string              `json:"icon"`
	Namespace         string               `json:"namespace"`
	Owner             *string              `json:"owner"`
	OwnerName         *string              `json:"ownerName"`
	Collaborators     []string             `json:"collaborators"`
	CollaboratorNames map[string]string    `json:"collaboratorNames"`
	SourceType        string               `json:"sourceType"`
}

func (s *Server) toApplicationViews(ctx context.Context, applications []store.Application, withCollaborators bool) ([]applicationView, error) {
	userIDSet := map[string]struct{}{}
	for _, application := range applications {
		if application.Owner != nil && *application.Owner != "" {
			userIDSet[*application.Owner] = struct{}{}
		}
	}

	collaborators := map[string][]string{}
	sourceTypes := map[string]string{}
	if len(applications) > 0 {
		var err error
		if withCollaborators {
			if collaborators, err = s.store.CollaboratorsByApplication(ctx, applications); err != nil {
				return nil, err
			}
			for _, userIDs := range collaborators {
				for _, userID := range userIDs {
					userIDSet[userID] = struct{}{}
				}
			}
		}
		if sourceTypes, err = s.store.SourceTypesByApplication(ctx, applications); err != nil {
			return nil, err
		}
	}

	userIDs := make([]string, 0, len(userIDSet))
	for userID := range userIDSet {
		userIDs = append(userIDs, userID)
	}
	usernames, err := s.store.UsernamesByIDs(ctx, userIDs)
	if err != nil {
		return nil, err
	}

	views := make([]applicationView, 0, len(applications))
	for _, application := range applications {
		key := application.Namespace + "/" + application.Name
		view := applicationView{
			ID:                application.ID,
			CreatedTime:       application.CreatedTime,
			Name:              application.Name,
			Description:       application.Description,
			Icon:              application.Icon,
			Namespace:         application.Namespace,
			Owner:             application.Owner,
			Collaborators:     []string{},
			CollaboratorNames: map[string]string{},
			SourceType:        "GIT",
		}
		if application.Owner != nil {
			if ownerName, found := usernames[*application.Owner]; found {
				view.OwnerName = &ownerName
			}
		}
		for _, userID := range collaborators[key] {
			view.Collaborators = append(view.Collaborators, userID)
			if username, found := usernames[userID]; found {
				view.CollaboratorNames[userID] = username
			}
		}
		if sourceType, found := sourceTypes[key]; found {
			view.SourceType = sourceType
		}
		views = append(views, view)
	}
	return views, nil
}

func queryInt(c *gin.Context, name string, fallback int) int {
	if value, err := strconv.Atoi(c.Query(name)); err == nil && value > 0 {
		return value
	}
	return fallback
}

func (s *Server) listApplications(c *gin.Context) {
	namespace := c.Param("namespace")
	keyword := c.Query("keyword")
	page := queryInt(c, "page", 1)
	size := queryInt(c, "size", 10)

	ownerID := ""
	if c.Query("ownerOnly") == "true" {
		ownerID = principalFrom(c).UserID
	}

	ctx := c.Request.Context()
	total, applications, err := s.store.PageApplications(ctx, namespace, keyword, ownerID, principalFrom(c).UserID, page, size)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	views, err := s.toApplicationViews(ctx, applications, true)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(NewPage(total, views, size)))
}

func (s *Server) searchApplications(c *gin.Context) {
	keyword := c.Query("keyword")
	size := queryInt(c, "size", 5)
	ctx := c.Request.Context()
	applications, err := s.store.SearchApplications(ctx, keyword, size)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	// Search spans namespaces; resolve per-namespace metadata individually.
	views := make([]applicationView, 0, len(applications))
	for _, application := range applications {
		singleView, err := s.toApplicationViews(ctx, []store.Application{application}, false)
		if err != nil {
			c.JSON(http.StatusInternalServerError, fail(err.Error()))
			return
		}
		views = append(views, singleView...)
	}
	c.JSON(http.StatusOK, ok(views))
}

type applicationProfileRequest struct {
	Name          string   `json:"name"`
	Description   string   `json:"description"`
	Icon          string   `json:"icon"`
	Owner         string   `json:"owner"`
	Collaborators []string `json:"collaborators"`
}

func (s *Server) createApplication(c *gin.Context) {
	var request applicationProfileRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	if !domain.IsValidResourceName(request.Name) {
		c.JSON(http.StatusOK, fail("Invalid resource name"))
		return
	}
	principal := principalFrom(c)
	id, err := s.store.CreateApplication(c.Request.Context(), c.Param("namespace"),
		request.Name, request.Description, request.Icon, principal.UserID)
	if errors.Is(err, store.ErrDuplicateName) {
		c.JSON(http.StatusOK, fail("Application name already exists"))
		return
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(id))
}

func (s *Server) updateApplication(c *gin.Context) {
	var request applicationProfileRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	err := s.store.UpdateApplicationProfile(c.Request.Context(), c.Param("namespace"), c.Param("name"),
		request.Description, request.Owner, request.Icon, request.Collaborators)
	if errors.Is(err, store.ErrNotFound) {
		c.JSON(http.StatusOK, fail("Application not found"))
		return
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

type buildConfigRequest struct {
	SourceType         *string                        `json:"sourceType"`
	Repository         *string                        `json:"repository"`
	DockerFileConfig   *store.DockerFileConfig        `json:"dockerFileConfig"`
	BuildImage         *string                        `json:"buildImage"`
	EnvironmentConfigs []store.BuildEnvironmentConfig `json:"environmentConfigs"`
}

func (s *Server) updateBuildConfig(c *gin.Context) {
	var request buildConfigRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	err := s.store.SaveBuildConfig(c.Request.Context(), c.Param("namespace"), c.Param("name"),
		request.SourceType, request.Repository, request.DockerFileConfig, request.BuildImage,
		request.EnvironmentConfigs)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

func (s *Server) updateBuildEnvironmentConfigs(c *gin.Context) {
	var configs []store.BuildEnvironmentConfig
	if err := c.ShouldBindJSON(&configs); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	if err := s.store.SaveBuildEnvironmentConfigs(c.Request.Context(), c.Param("namespace"), c.Param("name"), configs); err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

type runtimeSpecRequest struct {
	EnvironmentConfigs []store.RuntimeEnvironmentConfig `json:"environmentConfigs"`
	HealthCheck        *store.HealthCheck               `json:"healthCheck"`
}

// storedRuntimeEnvironmentConfigs reads the configs a runtime-spec save is
// about to replace, so the apply step can tell what actually changed.
func (s *Server) storedRuntimeEnvironmentConfigs(ctx context.Context, namespace, applicationName string) []store.RuntimeEnvironmentConfig {
	spec, err := s.store.FindRuntimeSpec(ctx, namespace, applicationName)
	if err != nil || spec == nil {
		return nil
	}
	return spec.EnvironmentConfigs
}

func (s *Server) updateRuntimeSpec(c *gin.Context) {
	var request runtimeSpecRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	ctx := c.Request.Context()
	namespace, applicationName := c.Param("namespace"), c.Param("name")
	existingConfigs := s.storedRuntimeEnvironmentConfigs(ctx, namespace, applicationName)
	err := s.store.SaveRuntimeSpec(ctx, namespace, applicationName,
		request.EnvironmentConfigs, request.HealthCheck)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	s.engine.ApplyRuntimeSpecNow(ctx, namespace, applicationName, request.EnvironmentConfigs, existingConfigs)
	c.JSON(http.StatusOK, ok(true))
}

func (s *Server) updateRuntimeSpecEnvironmentConfigs(c *gin.Context) {
	var configs []store.RuntimeEnvironmentConfig
	if err := c.ShouldBindJSON(&configs); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	ctx := c.Request.Context()
	namespace, applicationName := c.Param("namespace"), c.Param("name")
	existingConfigs := s.storedRuntimeEnvironmentConfigs(ctx, namespace, applicationName)
	if err := s.store.SaveRuntimeSpecEnvironmentConfigs(ctx, namespace, applicationName, configs); err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	s.engine.ApplyRuntimeSpecNow(ctx, namespace, applicationName, configs, existingConfigs)
	c.JSON(http.StatusOK, ok(true))
}

type serviceConfigRequest struct {
	Port               *int                                  `json:"port"`
	InternalPorts      []int                                 `json:"internalPorts"`
	EnvironmentConfigs []store.ServiceEnvironmentConfigInput `json:"environmentConfigs"`
}

func (s *Server) updateServiceConfig(c *gin.Context) {
	var request serviceConfigRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	err := s.store.SaveServiceConfig(c.Request.Context(), c.Param("namespace"), c.Param("name"),
		request.Port, request.InternalPorts, request.EnvironmentConfigs)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}

// storedExpertEnvironmentConfigs reads the configs an expert-config save is
// about to replace, so the apply step can tell what actually changed.
func (s *Server) storedExpertEnvironmentConfigs(ctx context.Context, namespace, applicationName string) []store.ExpertEnvironmentConfig {
	config, err := s.store.FindExpertConfig(ctx, namespace, applicationName)
	if err != nil || config == nil {
		return nil
	}
	return config.EnvironmentConfigs
}

func (s *Server) updateExpertConfig(c *gin.Context) {
	var request struct {
		EnvironmentConfigs []store.ExpertEnvironmentConfig `json:"environmentConfigs"`
	}
	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	ctx := c.Request.Context()
	namespace, applicationName := c.Param("namespace"), c.Param("name")
	existingConfigs := s.storedExpertEnvironmentConfigs(ctx, namespace, applicationName)
	if err := s.store.SaveExpertConfig(ctx, namespace, applicationName, request.EnvironmentConfigs); err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	s.engine.ApplyExpertConfigNow(ctx, namespace, applicationName, request.EnvironmentConfigs, existingConfigs)
	c.JSON(http.StatusOK, ok(true))
}

func (s *Server) updateEnvironmentBindings(c *gin.Context) {
	var bindings []struct {
		Environment string `json:"environment"`
	}
	if err := c.ShouldBindJSON(&bindings); err != nil {
		c.JSON(http.StatusOK, fail("Invalid request"))
		return
	}
	names := make([]string, 0, len(bindings))
	for _, binding := range bindings {
		names = append(names, binding.Environment)
	}
	if err := s.store.ReplaceEnvironmentBindings(c.Request.Context(), c.Param("namespace"), c.Param("name"), names); err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	c.JSON(http.StatusOK, ok(true))
}
