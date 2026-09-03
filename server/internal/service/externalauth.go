package service

import (
	"context"
	"log/slog"
	"sort"
	"strings"

	"github.com/wellch4n/oops/server/internal/domain"
)

// ExternalIdentity is who a provider says the caller is. It is deliberately
// provider-neutral: the provider adapter knows nothing about OOPS accounts, and
// this service knows nothing about OAuth.
type ExternalIdentity struct {
	ProviderUserID string
	Name           string
	Email          string
}

// ExternalAuthProvider is one login provider. Adding another means one more
// implementation and one more line in the registry — nothing here changes.
type ExternalAuthProvider interface {
	// Name is the lowercase identifier the URL carries, e.g. "feishu".
	Name() string
	// LoginURL is where the browser is sent to start the flow.
	LoginURL() string
	// Authenticate exchanges an authorization code for an identity.
	Authenticate(ctx context.Context, code string) (*ExternalIdentity, error)
}

// ExternalAuthService turns a provider identity into an OOPS session.
type ExternalAuthService struct {
	services  *Services
	providers map[string]ExternalAuthProvider
}

// RegisterExternalProvider adds a provider. Only configured ones are
// registered, so the registry is exactly the set of enabled providers and
// `getEnabledProviders` is just its keys.
func (s *Services) RegisterExternalProvider(provider ExternalAuthProvider) {
	s.ExternalAuth.providers[strings.ToLower(provider.Name())] = provider
}

// EnabledProviders names the providers the login page should offer a button for.
func (s *ExternalAuthService) EnabledProviders() []string {
	names := make([]string, 0, len(s.providers))
	for name := range s.providers {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}

// LoginURL is where the browser starts the flow for one provider.
func (s *ExternalAuthService) LoginURL(provider string) (string, error) {
	found, err := s.provider(provider)
	if err != nil {
		return "", err
	}
	return found.LoginURL(), nil
}

// Authenticate completes the flow: it exchanges the code for an identity, finds
// or creates the matching account, links it, and returns a session token.
func (s *ExternalAuthService) Authenticate(ctx context.Context, provider, code string, sign func(userID, username, role string) (string, error)) (string, error) {
	if strings.TrimSpace(code) == "" {
		return "", domain.Biz("Authorization code is required")
	}
	found, err := s.provider(provider)
	if err != nil {
		return "", err
	}
	identity, err := found.Authenticate(ctx, code)
	if err != nil {
		slog.Error("external authentication failed", "provider", provider, "error", err)
		return "", domain.Bizf("Authentication failed: %s", err.Error())
	}

	user, err := s.resolveUser(ctx, domain.ExternalAccountProvider(strings.ToUpper(provider)), identity)
	if err != nil {
		return "", err
	}
	// An account disabled in OOPS stays disabled however the caller signed in.
	if !user.IsEnabled() {
		return "", domain.Biz("Account is disabled")
	}
	return sign(user.ID, domain.Deref(user.Username), user.RoleName())
}

// resolveUser finds the OOPS account behind an identity, creating one the first
// time somebody signs in with it.
func (s *ExternalAuthService) resolveUser(ctx context.Context, provider domain.ExternalAccountProvider, identity *ExternalIdentity) (*domain.User, error) {
	accounts := s.services.Store.ExternalAccounts()
	account, err := accounts.FindByProviderAndProviderUserID(ctx, provider, identity.ProviderUserID)
	if err != nil {
		return nil, err
	}
	if account != nil {
		user, err := s.services.Users.FindByID(ctx, domain.Deref(account.UserID))
		if err != nil {
			return nil, err
		}
		if user != nil {
			return user, nil
		}
		// The link outlived the account it pointed at — an admin deleted the
		// user. Re-point it rather than stranding the identity.
		user, err = s.findOrCreateUser(ctx, identity)
		if err != nil {
			return nil, err
		}
		account.UserID = &user.ID
		account.Email = domain.StringOrNil(identity.Email)
		if _, err := accounts.Save(ctx, account); err != nil {
			return nil, err
		}
		return user, nil
	}

	user, err := s.findOrCreateUser(ctx, identity)
	if err != nil {
		return nil, err
	}
	_, err = accounts.Save(ctx, &domain.ExternalAccount{
		Provider:       provider,
		ProviderUserID: &identity.ProviderUserID,
		UserID:         &user.ID,
		Email:          domain.StringOrNil(identity.Email),
	})
	if err != nil {
		return nil, err
	}
	return user, nil
}

// findOrCreateUser matches an existing account by email before making a new
// one, so somebody who already has an OOPS login keeps it rather than acquiring
// a second identity.
func (s *ExternalAuthService) findOrCreateUser(ctx context.Context, identity *ExternalIdentity) (*domain.User, error) {
	if email := strings.TrimSpace(identity.Email); email != "" {
		existing, err := s.services.Users.FindByEmail(ctx, email)
		if err != nil {
			return nil, err
		}
		if existing != nil {
			return existing, nil
		}
	}
	username := strings.TrimSpace(identity.Name)
	if username == "" {
		username = "feishu_" + identity.ProviderUserID
	}
	// No password: the account can only be used through the provider until an
	// administrator sets one.
	return s.services.Users.Create(ctx, username, identity.Email, "", domain.RoleUser)
}

func (s *ExternalAuthService) provider(name string) (ExternalAuthProvider, error) {
	found, ok := s.providers[strings.ToLower(strings.TrimSpace(name))]
	if !ok {
		return nil, domain.Bizf("Unsupported external login provider: %s", name)
	}
	return found, nil
}
