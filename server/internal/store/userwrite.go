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
	_, err = s.db.ExecContext(ctx,
		`INSERT INTO user (id, created_time, username, email, password, role, enabled)
		 VALUES (?, ?, ?, ?, ?, 'USER', 1)`,
		NewNanoID(), Now(), username, email, hash)
	if isDuplicateKey(err) {
		return ErrDuplicateUser
	}
	return err
}

// UpdateUser mirrors UserService.updateUser: role and email always set, the
// password only when non-blank, enabled only when provided.
func (s *Store) UpdateUser(ctx context.Context, id, role, email, rawPassword string, enabled *bool) error {
	query := "UPDATE user SET role = ?, email = ?"
	args := []any{role, email}
	if rawPassword != "" {
		hash, err := hashPassword(rawPassword)
		if err != nil {
			return err
		}
		query += ", password = ?"
		args = append(args, hash)
	}
	if enabled != nil {
		query += ", enabled = ?"
		args = append(args, *enabled)
	}
	query += " WHERE id = ?"
	args = append(args, id)
	_, err := s.db.ExecContext(ctx, query, args...)
	return err
}

func (s *Store) DeleteUser(ctx context.Context, id string) error {
	_, err := s.db.ExecContext(ctx, "DELETE FROM user WHERE id = ?", id)
	return err
}

func (s *Store) UpdateUserEmail(ctx context.Context, id, email string) error {
	_, err := s.db.ExecContext(ctx, "UPDATE user SET email = ? WHERE id = ?", email, id)
	return err
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
	_, err = s.db.ExecContext(ctx, "UPDATE user SET password = ? WHERE id = ?", hash, id)
	return err
}

// ResetAccessToken mirrors resetMyAccessToken: "sk-oops-" + NanoId.
func (s *Store) ResetAccessToken(ctx context.Context, id string) (string, error) {
	token := "sk-oops-" + NewNanoID()
	_, err := s.db.ExecContext(ctx, "UPDATE user SET access_token = ? WHERE id = ?", token, id)
	return token, err
}

// FindUserByAccessToken backs the /openapi surface's OpenApiAuthFilter.
func (s *Store) FindUserByAccessToken(ctx context.Context, accessToken string) (*User, error) {
	row := s.db.QueryRowContext(ctx,
		"SELECT "+userColumns+" FROM user WHERE access_token = ? LIMIT 1", accessToken)
	return scanUser(row)
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
		var enabledAdmins int
		if err := s.db.QueryRowContext(ctx,
			"SELECT COUNT(*) FROM user WHERE role = 'ADMIN' AND enabled = 1").Scan(&enabledAdmins); err != nil {
			return false, err
		}
		if enabledAdmins <= 1 {
			return false, nil // last-admin guard; the caller logs
		}
	}
	_, err = s.db.ExecContext(ctx, "UPDATE user SET enabled = 0 WHERE id = ?", id)
	return err == nil, err
}
