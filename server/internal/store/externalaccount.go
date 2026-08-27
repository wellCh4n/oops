package store

import (
	"context"
	"database/sql"
	"errors"
)

// ExternalAccount mirrors the entity.
type ExternalAccount struct {
	ID             string         `json:"id"`
	CreatedTime    *LocalDateTime `json:"createdTime"`
	Email          *string        `json:"email"`
	Provider       string         `json:"provider"`
	ProviderUserID string         `json:"providerUserId"`
	UserID         string         `json:"userId"`
}

func (s *Store) FindExternalAccountByProviderUser(ctx context.Context, provider, providerUserID string) (*ExternalAccount, error) {
	var account ExternalAccount
	err := s.db.QueryRowContext(ctx,
		`SELECT id, created_time, email, provider, provider_user_id, user_id
		 FROM external_account WHERE provider = ? AND provider_user_id = ? LIMIT 1`,
		provider, providerUserID).
		Scan(&account.ID, &account.CreatedTime, &account.Email, &account.Provider,
			&account.ProviderUserID, &account.UserID)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return &account, err
}

func (s *Store) FindExternalAccountByUser(ctx context.Context, provider, userID string) (*ExternalAccount, error) {
	var account ExternalAccount
	err := s.db.QueryRowContext(ctx,
		`SELECT id, created_time, email, provider, provider_user_id, user_id
		 FROM external_account WHERE provider = ? AND user_id = ? LIMIT 1`,
		provider, userID).
		Scan(&account.ID, &account.CreatedTime, &account.Email, &account.Provider,
			&account.ProviderUserID, &account.UserID)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return &account, err
}

func (s *Store) SaveExternalAccount(ctx context.Context, provider, providerUserID, userID, email string) error {
	existing, err := s.FindExternalAccountByProviderUser(ctx, provider, providerUserID)
	if errors.Is(err, ErrNotFound) {
		_, err := s.db.ExecContext(ctx,
			`INSERT INTO external_account (id, created_time, email, provider, provider_user_id, user_id)
			 VALUES (?, ?, ?, ?, ?, ?)`,
			NewNanoID(), Now(), email, provider, providerUserID, userID)
		return err
	}
	if err != nil {
		return err
	}
	_, err = s.db.ExecContext(ctx,
		"UPDATE external_account SET user_id = ?, email = ? WHERE id = ?", userID, email, existing.ID)
	return err
}

// FindUserByEmail backs the OAuth account matching.
func (s *Store) FindUserByEmail(ctx context.Context, email string) (*User, error) {
	row := s.db.QueryRowContext(ctx,
		"SELECT "+userColumns+" FROM user WHERE email = ? LIMIT 1", email)
	user, err := scanUser(row)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return user, err
}

// CreateExternalUser creates an OAuth-provisioned USER account with no password.
func (s *Store) CreateExternalUser(ctx context.Context, username, email string) (*User, error) {
	id := NewNanoID()
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO user (id, created_time, username, email, password, role, enabled)
		 VALUES (?, ?, ?, ?, NULL, 'USER', 1)`,
		id, Now(), username, email)
	if err != nil {
		return nil, err
	}
	return s.FindUserByID(ctx, id)
}
