package service

import (
	"context"
	"log/slog"
	"strings"

	"github.com/wellch4n/oops/server/internal/crypto"
	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/store"
)

// UserService is accounts, passwords and the OpenAPI access token.
type UserService struct {
	store *store.Store
}

func (s *UserService) FindByID(ctx context.Context, id string) (*domain.User, error) {
	return s.store.Users().FindByID(ctx, id)
}

func (s *UserService) FindByUsername(ctx context.Context, username string) (*domain.User, error) {
	return s.store.Users().FindByUsername(ctx, username)
}

func (s *UserService) FindByAccessToken(ctx context.Context, token string) (*domain.User, error) {
	return s.store.Users().FindByAccessToken(ctx, token)
}

// FindByUsernameOrEmail backs login, which accepts either identifier.
func (s *UserService) FindByUsernameOrEmail(ctx context.Context, identifier string) (*domain.User, error) {
	return s.store.Users().FindByUsernameOrEmail(ctx, identifier)
}

// CheckPassword verifies a bcrypt hash. A user with no password can never log
// in, rather than matching the empty one.
func (s *UserService) CheckPassword(user *domain.User, rawPassword string) bool {
	if user == nil || domain.Deref(user.Password) == "" {
		return false
	}
	return crypto.CheckPassword(rawPassword, domain.Deref(user.Password))
}

func (s *UserService) List(ctx context.Context) ([]domain.User, error) {
	return s.store.Users().FindAll(ctx)
}

func (s *UserService) ListPage(ctx context.Context, keyword string, page, size int) (store.Page[domain.User], error) {
	return s.store.Users().FindPage(ctx, keyword, page, size)
}

// UsernamesByID resolves ids to usernames in one query, for the owner and
// collaborator columns.
func (s *UserService) UsernamesByID(ctx context.Context, ids []string) (map[string]string, error) {
	if len(ids) == 0 {
		return map[string]string{}, nil
	}
	users, err := s.store.Users().FindAllByID(ctx, ids)
	if err != nil {
		return nil, err
	}
	names := make(map[string]string, len(users))
	for _, user := range users {
		if _, seen := names[user.ID]; !seen {
			names[user.ID] = domain.Deref(user.Username)
		}
	}
	return names, nil
}

// Create adds an account. The password is hashed; a blank one leaves the
// account unable to log in until an admin sets one.
func (s *UserService) Create(ctx context.Context, username, email, rawPassword string, role domain.UserRole) (*domain.User, error) {
	user := &domain.User{
		Username: domain.StringOrNil(username),
		Email:    domain.StringOrNil(email),
		Role:     &role,
		Enabled:  domain.Ptr(true),
	}
	if strings.TrimSpace(rawPassword) != "" {
		hash, err := crypto.HashPassword(rawPassword)
		if err != nil {
			return nil, err
		}
		user.Password = &hash
	}
	return s.store.Users().Save(ctx, user)
}

// Update is a whole-record write, not a patch: an omitted role or email clears
// it. That is what the Java endpoint did, and the suite pins the behaviour.
func (s *UserService) Update(ctx context.Context, id string, role *domain.UserRole, email, rawPassword string, enabled *bool) error {
	user, err := s.store.Users().FindByID(ctx, id)
	if err != nil || user == nil {
		return err
	}
	user.Role = role
	user.Email = domain.StringOrNil(email)
	if strings.TrimSpace(rawPassword) != "" {
		hash, err := crypto.HashPassword(rawPassword)
		if err != nil {
			return err
		}
		user.Password = &hash
	}
	if enabled != nil {
		user.Enabled = enabled
	}
	_, err = s.store.Users().Save(ctx, user)
	return err
}

func (s *UserService) Delete(ctx context.Context, id string) error {
	return s.store.Users().DeleteByID(ctx, id)
}

// UpdateMyProfile changes only the caller's email.
func (s *UserService) UpdateMyProfile(ctx context.Context, userID, email string) error {
	user, err := s.store.Users().FindByID(ctx, userID)
	if err != nil {
		return err
	}
	if user == nil {
		return domain.Biz("User not found")
	}
	user.Email = domain.StringOrNil(strings.TrimSpace(email))
	_, err = s.store.Users().Save(ctx, user)
	return err
}

// ChangeMyPassword requires the current password.
func (s *UserService) ChangeMyPassword(ctx context.Context, userID, oldPassword, newPassword string) error {
	if strings.TrimSpace(newPassword) == "" {
		return domain.Biz("New password is required")
	}
	user, err := s.store.Users().FindByID(ctx, userID)
	if err != nil {
		return err
	}
	if user == nil {
		return domain.Biz("User not found")
	}
	if !s.CheckPassword(user, oldPassword) {
		return domain.Biz("Old password is incorrect")
	}
	hash, err := crypto.HashPassword(newPassword)
	if err != nil {
		return err
	}
	user.Password = &hash
	_, err = s.store.Users().Save(ctx, user)
	return err
}

// ResetMyAccessToken mints a new OpenAPI token, invalidating the old one.
func (s *UserService) ResetMyAccessToken(ctx context.Context, userID string) (string, error) {
	user, err := s.store.Users().FindByID(ctx, userID)
	if err != nil {
		return "", err
	}
	if user == nil {
		return "", domain.Biz("User not found")
	}
	token := domain.NewAccessToken()
	user.AccessToken = &token
	if _, err := s.store.Users().Save(ctx, user); err != nil {
		return "", err
	}
	return token, nil
}

// Deactivate disables an account on an external signal (a resignation synced
// from the directory). It refuses to disable the last enabled admin: an
// external directory has no idea it is holding the only key to the
// installation. Returns whether anything changed.
func (s *UserService) Deactivate(ctx context.Context, id string) (bool, error) {
	user, err := s.store.Users().FindByID(ctx, id)
	if err != nil || user == nil || !user.IsEnabled() {
		return false, err
	}
	if user.Role != nil && *user.Role == domain.RoleAdmin {
		enabledAdmins, err := s.store.Users().CountEnabledByRole(ctx, domain.RoleAdmin)
		if err != nil {
			return false, err
		}
		if enabledAdmins <= 1 {
			slog.Warn("refusing to disable the last enabled admin", "userId", id)
			return false, nil
		}
	}
	user.Enabled = domain.Ptr(false)
	_, err = s.store.Users().Save(ctx, user)
	return err == nil, err
}

// EnsureDefaultAdmin creates the admin account on an empty installation.
func (s *UserService) EnsureDefaultAdmin(ctx context.Context, password string) error {
	exists, err := s.store.Users().ExistsByRole(ctx, domain.RoleAdmin)
	if err != nil || exists {
		return err
	}
	if _, err := s.Create(ctx, "admin", "", password, domain.RoleAdmin); err != nil {
		return err
	}
	slog.Info("created the default admin account", "username", "admin")
	return nil
}

// FindByEmail loads a user by exact email; nil when absent.
func (s *UserService) FindByEmail(ctx context.Context, email string) (*domain.User, error) {
	return s.store.Users().FindByEmail(ctx, email)
}
