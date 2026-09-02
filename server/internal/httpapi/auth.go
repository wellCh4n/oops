package httpapi

import (
	"context"
	"net/http"
	"strings"
	"time"

	"github.com/wellch4n/oops/server/internal/domain"
)

// Principal is the authenticated caller.
type Principal struct {
	UserID   string
	Username string
	Role     string
}

func (p Principal) IsAdmin() bool { return p.Role == string(domain.RoleAdmin) }

type principalKey struct{}

// PrincipalFrom returns the caller or nil.
func PrincipalFrom(ctx context.Context) *Principal {
	p, _ := ctx.Value(principalKey{}).(*Principal)
	return p
}

// CallerID returns the caller's user id or "".
func CallerID(ctx context.Context) string {
	if p := PrincipalFrom(ctx); p != nil {
		return p.UserID
	}
	return ""
}

// TokenClaims are the JWT claims the middleware needs.
type TokenClaims struct {
	UserID   string
	Username string
	Role     string
}

// TokenParser verifies a UI JWT.
type TokenParser interface {
	ParseToken(token string) (TokenClaims, bool)
}

// UserLookup resolves users for authentication.
type UserLookup interface {
	FindUserByID(ctx context.Context, id string) (*domain.User, error)
	FindUserByUsername(ctx context.Context, username string) (*domain.User, error)
	FindUserByAccessToken(ctx context.Context, token string) (*domain.User, error)
}

// Authenticator implements the two Java filters.
type Authenticator struct {
	Tokens TokenParser
	Users  UserLookup
}

// errorBody mirrors Spring Boot's /error JSON for 401/403/405.
type errorBody struct {
	Timestamp string `json:"timestamp"`
	Status    int    `json:"status"`
	Error     string `json:"error"`
	Path      string `json:"path"`
}

func writeSecurityError(w http.ResponseWriter, r *http.Request, status int) {
	writeJSON(w, status, errorBody{
		Timestamp: time.Now().UTC().Format("2006-01-02T15:04:05.000+00:00"),
		Status:    status,
		Error:     http.StatusText(status),
		Path:      r.URL.Path,
	})
}

func principalOf(user *domain.User) *Principal {
	return &Principal{UserID: user.ID, Username: domain.Deref(user.Username), Role: user.RoleName()}
}

// resolveJWT mirrors JwtAuthFilter: header → ?token= → cookie auth_token.
func (a *Authenticator) resolveJWT(r *http.Request) *Principal {
	token := ""
	if header := r.Header.Get("Authorization"); strings.HasPrefix(header, "Bearer ") {
		token = header[len("Bearer "):]
	} else if query := r.URL.Query().Get("token"); strings.TrimSpace(query) != "" {
		token = query
	} else if cookie, err := r.Cookie("auth_token"); err == nil && strings.TrimSpace(cookie.Value) != "" {
		token = cookie.Value
	}
	if token == "" {
		return nil
	}
	claims, ok := a.Tokens.ParseToken(token)
	if !ok {
		return nil
	}
	var user *domain.User
	var err error
	if strings.TrimSpace(claims.UserID) != "" {
		user, err = a.Users.FindUserByID(r.Context(), claims.UserID)
	} else {
		user, err = a.Users.FindUserByUsername(r.Context(), claims.Username)
	}
	if err != nil || user == nil || !user.IsEnabled() {
		return nil
	}
	return principalOf(user)
}

// RequireJWT protects /api/**: anonymous → bare 401.
func (a *Authenticator) RequireJWT(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		principal := a.resolveJWT(r)
		if principal == nil {
			writeSecurityError(w, r, http.StatusUnauthorized)
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), principalKey{}, principal)))
	})
}

// RequireAccessToken protects /openapi/**: Bearer user access token only.
func (a *Authenticator) RequireAccessToken(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		header := r.Header.Get("Authorization")
		if !strings.HasPrefix(header, "Bearer ") {
			writeSecurityError(w, r, http.StatusUnauthorized)
			return
		}
		token := strings.TrimSpace(header[len("Bearer "):])
		if token == "" {
			writeSecurityError(w, r, http.StatusUnauthorized)
			return
		}
		user, err := a.Users.FindUserByAccessToken(r.Context(), token)
		if err != nil || user == nil || (user.Enabled != nil && !*user.Enabled) {
			writeSecurityError(w, r, http.StatusUnauthorized)
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), principalKey{}, principalOf(user))))
	})
}

// RequireAdmin mirrors @PreAuthorize("hasRole('ADMIN')") including the Java
// quirk that a denial surfaces as HTTP 200 "Internal server error".
func RequireAdmin(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if p := PrincipalFrom(r.Context()); p == nil || !p.IsAdmin() {
			Fail(w, domain.InternalServerErrorMessage)
			return
		}
		next(w, r)
	}
}

// HiddenOnOpenAPI answers 405 for @OpenApiHidden methods on /openapi/**.
func HiddenOnOpenAPI(w http.ResponseWriter, r *http.Request) {
	writeSecurityError(w, r, http.StatusMethodNotAllowed)
}
