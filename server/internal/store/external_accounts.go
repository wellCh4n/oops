package store

import (
	"context"

	"github.com/wellch4n/oops/server/internal/domain"
)

// ExternalAccountRepo owns the link between an OOPS user and an account at an
// OAuth provider.
type ExternalAccountRepo struct {
	store *Store
}

func externalAccountFromRow(row externalAccountRow) domain.ExternalAccount {
	return domain.ExternalAccount{
		ID:             row.ID,
		CreatedTime:    row.CreatedTime,
		Email:          orNil(row.Email),
		Provider:       domain.ExternalAccountProvider(row.Provider),
		ProviderUserID: orNil(row.ProviderUserID),
		UserID:         orNil(row.UserID),
	}
}

func firstExternalAccount(rows []externalAccountRow, err error) (*domain.ExternalAccount, error) {
	if err != nil || len(rows) == 0 {
		return nil, err
	}
	account := externalAccountFromRow(rows[0])
	return &account, nil
}

// FindByProviderAndProviderUserID resolves an inbound provider event to a
// linked account; nil when nobody linked that provider identity.
func (r *ExternalAccountRepo) FindByProviderAndProviderUserID(ctx context.Context, provider domain.ExternalAccountProvider, providerUserID string) (*domain.ExternalAccount, error) {
	return firstExternalAccount(list[externalAccountRow](ctx, r.store.db,
		`SELECT * FROM external_account WHERE provider = ? AND provider_user_id = ?`,
		string(provider), providerUserID))
}

// FindByProviderAndUserID finds where to send an OOPS user's notifications;
// nil when they have not linked this provider.
func (r *ExternalAccountRepo) FindByProviderAndUserID(ctx context.Context, provider domain.ExternalAccountProvider, userID string) (*domain.ExternalAccount, error) {
	return firstExternalAccount(list[externalAccountRow](ctx, r.store.db,
		`SELECT * FROM external_account WHERE provider = ? AND user_id = ?`,
		string(provider), userID))
}

// Save inserts or updates an external account link.
func (r *ExternalAccountRepo) Save(ctx context.Context, account *domain.ExternalAccount) (*domain.ExternalAccount, error) {
	found := false
	var err error
	if account.ID != "" {
		if found, err = exists(ctx, r.store.db, `SELECT 1 FROM external_account WHERE id = ? LIMIT 1`, account.ID); err != nil {
			return nil, err
		}
	}
	if found {
		_, err = execRows(ctx, r.store.db,
			`UPDATE external_account
SET created_time = ?, email = ?, provider = ?, provider_user_id = ?, user_id = ?
WHERE id = ?`,
			account.CreatedTime, domain.Deref(account.Email), string(account.Provider),
			domain.Deref(account.ProviderUserID), domain.Deref(account.UserID), account.ID)
	} else {
		account.ID = ensureID(account.ID)
		if account.CreatedTime.IsZero() {
			account.CreatedTime = domain.Now()
		}
		err = exec(ctx, r.store.db,
			`INSERT INTO external_account (id, created_time, email, provider, provider_user_id, user_id)
VALUES (?, ?, ?, ?, ?, ?)`,
			account.ID, account.CreatedTime, domain.Deref(account.Email), string(account.Provider),
			domain.Deref(account.ProviderUserID), domain.Deref(account.UserID))
	}
	if err != nil {
		return nil, err
	}
	return account, nil
}
