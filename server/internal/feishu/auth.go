package feishu

import (
	"context"
	"fmt"
	"net/url"
	"strings"

	"github.com/wellch4n/oops/server/internal/service"
)

const (
	authorizeURL  = "https://open.feishu.cn/open-apis/authen/v1/authorize"
	userTokenPath = "/authen/v1/access_token"
	userInfoPath  = "/authen/v1/user_info"
)

// Identity is who Feishu says the caller is.
type Identity struct {
	// ProviderUserID is the stable id the external_account row is keyed on.
	ProviderUserID string
	Name           string
	Email          string
}

// LoginURL is where the browser is sent to start the flow. state is fixed
// rather than random because the callback is matched by provider, not by a
// session — the code Feishu returns is single-use and short-lived, and the
// callback route is the only thing that accepts it.
func (c *Client) LoginURL(redirectURI string) string {
	return fmt.Sprintf("%s?app_id=%s&redirect_uri=%s&state=feishu",
		authorizeURL, url.QueryEscape(c.appID), url.QueryEscape(redirectURI))
}

// Authenticate exchanges the authorization code for the caller's identity.
//
// It is two round trips behind one app-level token: the code buys a user access
// token, and that token reads the profile. The app token is cached; the user
// token is used once and dropped.
func (c *Client) Authenticate(ctx context.Context, code string) (*Identity, error) {
	appToken, err := c.token(ctx, appTokenPath)
	if err != nil {
		return nil, fmt.Errorf("feishu app token: %w", err)
	}

	var tokenResponse struct {
		Data struct {
			AccessToken string `json:"access_token"`
		} `json:"data"`
	}
	if err := c.postJSON(ctx, userTokenPath, appToken, map[string]string{
		"grant_type": "authorization_code",
		"code":       code,
	}, &tokenResponse); err != nil {
		return nil, fmt.Errorf("feishu access token: %w", err)
	}
	userToken := tokenResponse.Data.AccessToken
	if userToken == "" {
		return nil, fmt.Errorf("feishu returned no user access token")
	}

	var infoResponse struct {
		Data struct {
			UserID          string `json:"user_id"`
			Name            string `json:"name"`
			Email           string `json:"email"`
			EnterpriseEmail string `json:"enterprise_email"`
		} `json:"data"`
	}
	if err := c.get(ctx, userInfoPath, userToken, &infoResponse); err != nil {
		return nil, fmt.Errorf("feishu user info: %w", err)
	}
	info := infoResponse.Data
	if strings.TrimSpace(info.UserID) == "" {
		return nil, fmt.Errorf("feishu user id is missing")
	}
	return &Identity{
		ProviderUserID: info.UserID,
		Name:           info.Name,
		Email:          resolveEmail(info.EnterpriseEmail, info.Email, info.UserID),
	}, nil
}

// resolveEmail prefers the work address, falls back to the personal one, and
// invents a unique unroutable one when Feishu shares neither — the account
// still needs something unique, and a shared placeholder would collide.
func resolveEmail(enterpriseEmail, userEmail, providerUserID string) string {
	if strings.TrimSpace(enterpriseEmail) != "" {
		return enterpriseEmail
	}
	if strings.TrimSpace(userEmail) != "" {
		return userEmail
	}
	return "feishu_" + sanitizeForEmail(providerUserID) + "@feishu.invalid"
}

func sanitizeForEmail(value string) string {
	return strings.Map(func(r rune) rune {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9',
			r == '.', r == '_', r == '-':
			return r
		default:
			return '_'
		}
	}, value)
}

// AuthProvider adapts the client to the service's ExternalAuthProvider port, so
// the service layer never learns anything about Feishu.
type AuthProvider struct {
	client      *Client
	redirectURI string
}

func NewAuthProvider(client *Client, redirectURI string) *AuthProvider {
	return &AuthProvider{client: client, redirectURI: redirectURI}
}

func (p *AuthProvider) Name() string { return "feishu" }

func (p *AuthProvider) LoginURL() string { return p.client.LoginURL(p.redirectURI) }

func (p *AuthProvider) Authenticate(ctx context.Context, code string) (*service.ExternalIdentity, error) {
	identity, err := p.client.Authenticate(ctx, code)
	if err != nil {
		return nil, err
	}
	return &service.ExternalIdentity{
		ProviderUserID: identity.ProviderUserID,
		Name:           identity.Name,
		Email:          identity.Email,
	}, nil
}
