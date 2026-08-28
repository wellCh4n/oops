package store

import (
	"context"
	"errors"

	"golang.org/x/crypto/bcrypt"
)

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
		ID: NewNanoID(), CreatedTime: Now(),
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
	token := "sk-oops-" + NewNanoID()
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
