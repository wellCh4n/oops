package store

import (
	"context"
	"errors"

	"github.com/wellch4n/oops/server/internal/domain"
)

// ExternalAccount mirrors the entity.
type ExternalAccount struct {
	ID             string         `json:"id"`
	CreatedTime    *LocalDateTime `json:"createdTime"`
	Email          *string        `json:"email"`
	Provider       string         `json:"provider"`
	ProviderUserID string         `json:"providerUserId" gorm:"column:provider_user_id"`
	UserID         string         `json:"userId" gorm:"column:user_id"`
}

func (ExternalAccount) TableName() string { return "external_account" }

func (s *Store) FindExternalAccountByProviderUser(ctx context.Context, provider, providerUserID string) (*ExternalAccount, error) {
	var account ExternalAccount
	err := s.orm.WithContext(ctx).
		Where("provider = ? AND provider_user_id = ?", provider, providerUserID).
		First(&account).Error
	return &account, notFound(err)
}

func (s *Store) FindExternalAccountByUser(ctx context.Context, provider, userID string) (*ExternalAccount, error) {
	var account ExternalAccount
	err := s.orm.WithContext(ctx).
		Where("provider = ? AND user_id = ?", provider, userID).
		First(&account).Error
	return &account, notFound(err)
}

func (s *Store) SaveExternalAccount(ctx context.Context, provider, providerUserID, userID, email string) error {
	existing, err := s.FindExternalAccountByProviderUser(ctx, provider, providerUserID)
	if errors.Is(err, ErrNotFound) {
		account := ExternalAccount{
			ID: domain.NewID(), CreatedTime: Now(),
			Email: &email, Provider: provider,
			ProviderUserID: providerUserID, UserID: userID,
		}
		return s.orm.WithContext(ctx).Create(&account).Error
	}
	if err != nil {
		return err
	}
	return s.orm.WithContext(ctx).Model(&ExternalAccount{}).
		Where("id = ?", existing.ID).
		Updates(map[string]any{"user_id": userID, "email": email}).Error
}

// FindUserByEmail backs the OAuth account matching.
func (s *Store) FindUserByEmail(ctx context.Context, email string) (*User, error) {
	var user User
	err := s.orm.WithContext(ctx).Where("email = ?", email).First(&user).Error
	return &user, notFound(err)
}

// CreateExternalUser creates an OAuth-provisioned USER account with no password.
func (s *Store) CreateExternalUser(ctx context.Context, username, email string) (*User, error) {
	user := User{
		ID: domain.NewID(), CreatedTime: Now(),
		Username: username, Email: email,
		Role: "USER", Enabled: true,
	}
	if err := s.orm.WithContext(ctx).Omit("password").Create(&user).Error; err != nil {
		return nil, err
	}
	return s.FindUserByID(ctx, user.ID)
}
