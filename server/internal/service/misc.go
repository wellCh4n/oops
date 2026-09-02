package service

import (
	"context"
	"strings"
	"time"

	"github.com/wellch4n/oops/server/internal/cron"
	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/gitremote"
)

// Features tells the UI which optional pages to render, before it has any
// reason to call the endpoints behind them.
func (s *Services) Features() FeaturesView {
	view := FeaturesView{
		Feishu:        s.Config.Feishu.Enabled,
		IDE:           s.Config.IDE.Enabled,
		IDEHTTPS:      s.Config.IDE.HTTPS,
		ObjectStorage: s.Config.ObjectStorage.Enabled,
	}
	// Only reported when the feature is on: the host is what the UI builds IDE
	// links from, and a stale one from a disabled config would produce links
	// that go nowhere.
	if s.Config.IDE.Enabled {
		view.IDEHost = domain.StringOrNil(s.Config.IDE.Domain)
	}
	return view
}

// PipelineQuery is the cross-namespace pipeline index search.
type PipelineQuery struct {
	Namespace       string `json:"namespace"`
	ApplicationName string `json:"applicationName"`
}

// ApplicationQuery is the cross-namespace application index search.
type ApplicationQuery struct {
	Namespace string `json:"namespace"`
	Name      string `json:"name"`
}

// QueryPipelines backs POST /api/index/pipelines.
func (s *Services) QueryPipelines(ctx context.Context, query PipelineQuery) ([]PipelineView, error) {
	pipelines, err := s.Store.Pipelines().Query(ctx, query.Namespace, query.ApplicationName)
	if err != nil {
		return nil, err
	}
	return s.Pipelines.toViews(ctx, pipelines)
}

// QueryApplications backs POST /api/index/applications.
func (s *Services) QueryApplications(ctx context.Context, query ApplicationQuery) ([]ApplicationView, error) {
	applications, err := s.Store.Applications().Query(ctx, query.Namespace, query.Name)
	if err != nil {
		return nil, err
	}
	return s.Applications.toViews(ctx, applications)
}

// NextCronRuns previews when a 5-field cron expression will next fire, for the
// scheduled-restart picker. Between one and five runs, like the UI offers.
func NextCronRuns(expression string, count int) ([]string, error) {
	if !cron.IsValid(expression) {
		return nil, domain.Biz("Invalid cron expression")
	}
	if count < 1 {
		count = 1
	}
	if count > 5 {
		count = 5
	}
	runs, err := cron.NextRuns(expression, count, time.Now())
	if err != nil {
		return nil, domain.Biz("Invalid cron expression")
	}
	rendered := make([]string, 0, len(runs))
	for _, run := range runs {
		rendered = append(rendered, run.Format("2006-01-02 15:04"))
	}
	return rendered, nil
}

// LoginCommand is the login request body.
type LoginCommand struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

// Login checks the credentials and mints a JWT. The same message covers an
// unknown account and a wrong password, so the response says nothing about
// which usernames exist.
func (s *Services) Login(ctx context.Context, request LoginCommand, sign func(userID, username, role string) (string, error)) (*LoginView, error) {
	user, err := s.Users.FindByUsernameOrEmail(ctx, strings.TrimSpace(request.Username))
	if err != nil {
		return nil, err
	}
	if user == nil || !s.Users.CheckPassword(user, request.Password) {
		return nil, domain.Biz("Invalid username or password")
	}
	if !user.IsEnabled() {
		return nil, domain.Biz("Account is disabled")
	}
	token, err := sign(user.ID, domain.Deref(user.Username), user.RoleName())
	if err != nil {
		return nil, err
	}
	return &LoginView{Token: token, ID: user.ID, Username: user.Username, Role: user.Role}, nil
}

// ListBranches offers the deploy dialog a branch picker. A ZIP application has
// no branches, which is an empty list rather than an error.
func (s *ApplicationService) ListBranches(ctx context.Context, namespace, name, environmentName string) ([]gitremote.Branch, error) {
	application, err := s.requireAggregate(ctx, namespace, name)
	if err != nil {
		return nil, err
	}
	buildConfig := application.BuildConfig
	if buildConfig == nil || buildConfig.EffectiveSourceType() != domain.SourceGit {
		return []gitremote.Branch{}, nil
	}
	repository := buildConfig.Repository()
	if domain.IsBlank(repository) {
		return []gitremote.Branch{}, nil
	}
	environment, err := s.services.environmentByName(ctx, environmentName)
	if err != nil {
		return nil, err
	}
	return s.services.Branches.ListBranches(ctx, environment, *repository)
}
