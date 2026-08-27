package httpapi

import (
	"context"
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"

	"github.com/wellch4n/oops/server/internal/store"
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

func (s *Server) toApplicationViews(ctx context.Context, namespace string, applications []store.Application, withCollaborators bool) ([]applicationView, error) {
	names := make([]string, 0, len(applications))
	userIDSet := map[string]struct{}{}
	for _, application := range applications {
		names = append(names, application.Name)
		if application.Owner != nil && *application.Owner != "" {
			userIDSet[*application.Owner] = struct{}{}
		}
	}

	collaborators := map[string][]string{}
	sourceTypes := map[string]string{}
	if len(applications) > 0 {
		var err error
		if withCollaborators {
			if collaborators, err = s.store.CollaboratorsByApplication(ctx, namespace, names); err != nil {
				return nil, err
			}
			for _, userIDs := range collaborators {
				for _, userID := range userIDs {
					userIDSet[userID] = struct{}{}
				}
			}
		}
		if sourceTypes, err = s.store.SourceTypesByApplication(ctx, namespace, names); err != nil {
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
		key := namespace + "/" + application.Name
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
	total, applications, err := s.store.PageApplications(ctx, namespace, keyword, ownerID, page, size)
	if err != nil {
		c.JSON(http.StatusInternalServerError, fail(err.Error()))
		return
	}
	views, err := s.toApplicationViews(ctx, namespace, applications, true)
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
		singleView, err := s.toApplicationViews(ctx, application.Namespace, []store.Application{application}, false)
		if err != nil {
			c.JSON(http.StatusInternalServerError, fail(err.Error()))
			return
		}
		views = append(views, singleView...)
	}
	c.JSON(http.StatusOK, ok(views))
}
