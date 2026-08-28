// User accounts: queries, paging, and admin/self-service writes.
package store

import (
	"context"
	"errors"
	"github.com/wellch4n/oops/server/internal/domain"
	"golang.org/x/crypto/bcrypt"
	"strings"
)

type User struct {
	ID          string         `json:"id"`
	CreatedTime *LocalDateTime `json:"createdTime"`
	Username    string         `json:"username"`
	Email       string         `json:"email"`
	Password    string         `json:"-"`
	Role        string         `json:"role"`
	Enabled     bool           `json:"enabled"`
	AccessToken *string        `json:"accessToken"`
}

func (User) TableName() string { return "user" }

func (s *Store) FindUserByUsernameOrEmail(ctx context.Context, login string) (*User, error) {
	var user User
	err := s.orm.WithContext(ctx).
		Where("username = ? OR email = ?", login, login).
		First(&user).Error
	return &user, notFound(err)
}

func (s *Store) FindUserByID(ctx context.Context, id string) (*User, error) {
	var user User
	err := s.orm.WithContext(ctx).Where("id = ?", id).First(&user).Error
	return &user, notFound(err)
}

func (s *Store) UsernamesByIDs(ctx context.Context, ids []string) (map[string]string, error) {
	names := map[string]string{}
	if len(ids) == 0 {
		return names, nil
	}
	var users []User
	if err := s.orm.WithContext(ctx).
		Select("id", "username").
		Where("id IN ?", ids).
		Find(&users).Error; err != nil {
		return nil, err
	}
	for _, user := range users {
		names[user.ID] = user.Username
	}
	return names, nil
}

// ListUsers backs GET /api/users: the whole roster, for owner/collaborator pickers.
func (s *Store) ListUsers(ctx context.Context) ([]User, error) {
	users := []User{}
	err := s.orm.WithContext(ctx).Order("created_time").Find(&users).Error
	return users, err
}

// PageUsers backs GET /api/users/page: keyword filter over username/email.
func (s *Store) PageUsers(ctx context.Context, keyword string, page, size int) (int64, []User, error) {
	pattern := "%" + strings.ToLower(keyword) + "%"
	query := s.orm.WithContext(ctx).Model(&User{}).
		Where("LOWER(username) LIKE ? OR LOWER(email) LIKE ?", pattern, pattern)

	var total int64
	if err := query.Count(&total).Error; err != nil {
		return 0, nil, err
	}
	users := []User{}
	err := query.Order("created_time DESC").
		Limit(size).Offset((max(page, 1) - 1) * size).
		Find(&users).Error
	return total, users, err
}

var (
	ErrWrongPassword = errors.New("old password is incorrect")
	ErrDuplicateUser = errors.New("username or email already exists")
)

func hashPassword(raw string) (string, error) {
	hash, err := bcrypt.GenerateFromPassword([]byte(raw), bcrypt.DefaultCost)
	return string(hash), err
}

func (s *Store) CreateUser(ctx context.Context, username, email, rawPassword string) error {
	hash, err := hashPassword(rawPassword)
	if err != nil {
		return err
	}
	user := User{
		ID: domain.NewID(), CreatedTime: Now(),
		Username: username, Email: email, Password: hash,
		Role: "USER", Enabled: true,
	}
	err = s.orm.WithContext(ctx).Create(&user).Error
	if isDuplicateKey(err) {
		return ErrDuplicateUser
	}
	return err
}

// UpdateUser mirrors UserService.updateUser: role and email always set, the
// password only when non-blank, enabled only when provided.
func (s *Store) UpdateUser(ctx context.Context, id, role, email, rawPassword string, enabled *bool) error {
	updates := map[string]any{"role": role, "email": email}
	if rawPassword != "" {
		hash, err := hashPassword(rawPassword)
		if err != nil {
			return err
		}
		updates["password"] = hash
	}
	if enabled != nil {
		updates["enabled"] = *enabled
	}
	return s.orm.WithContext(ctx).Model(&User{}).
		Where("id = ?", id).Updates(updates).Error
}

func (s *Store) DeleteUser(ctx context.Context, id string) error {
	return s.orm.WithContext(ctx).Where("id = ?", id).Delete(&User{}).Error
}

func (s *Store) UpdateUserEmail(ctx context.Context, id, email string) error {
	return s.orm.WithContext(ctx).Model(&User{}).
		Where("id = ?", id).Update("email", email).Error
}

func (s *Store) ChangePassword(ctx context.Context, id, oldPassword, newPassword string) error {
	user, err := s.FindUserByID(ctx, id)
	if err != nil {
		return ErrNotFound
	}
	if bcrypt.CompareHashAndPassword([]byte(user.Password), []byte(oldPassword)) != nil {
		return ErrWrongPassword
	}
	hash, err := hashPassword(newPassword)
	if err != nil {
		return err
	}
	return s.orm.WithContext(ctx).Model(&User{}).
		Where("id = ?", id).Update("password", hash).Error
}

// ResetAccessToken mirrors resetMyAccessToken: "sk-oops-" + NanoId.
func (s *Store) ResetAccessToken(ctx context.Context, id string) (string, error) {
	token := "sk-oops-" + domain.NewID()
	err := s.orm.WithContext(ctx).Model(&User{}).
		Where("id = ?", id).Update("access_token", token).Error
	return token, err
}

// FindUserByAccessToken backs the /openapi surface's OpenApiAuthFilter.
func (s *Store) FindUserByAccessToken(ctx context.Context, accessToken string) (*User, error) {
	var user User
	err := s.orm.WithContext(ctx).
		Where("access_token = ?", accessToken).First(&user).Error
	return &user, notFound(err)
}

// DeactivateUser mirrors UserService.deactivateUser: disable rather than
// delete, refusing to disable the last enabled admin (an external directory
// has no idea it is holding the only key to the installation). Returns whether
// anything changed.
func (s *Store) DeactivateUser(ctx context.Context, id string) (bool, error) {
	user, err := s.FindUserByID(ctx, id)
	if err != nil || !user.Enabled {
		return false, nil
	}
	if user.Role == "ADMIN" {
		var enabledAdmins int64
		if err := s.orm.WithContext(ctx).Model(&User{}).
			Where("role = 'ADMIN' AND enabled = 1").Count(&enabledAdmins).Error; err != nil {
			return false, err
		}
		if enabledAdmins <= 1 {
			return false, nil // last-admin guard; the caller logs
		}
	}
	err = s.orm.WithContext(ctx).Model(&User{}).
		Where("id = ?", id).Update("enabled", false).Error
	return err == nil, err
}
