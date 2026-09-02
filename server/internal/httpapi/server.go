package httpapi

import (
	"context"
	"net/http"
	"strconv"

	"github.com/go-chi/chi/v5"

	"github.com/wellch4n/oops/server/internal/auth"
	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/service"
)

// Server holds everything the handlers need. It is the only place the HTTP
// layer and the service layer meet.
type Server struct {
	services *service.Services
	tokens   *auth.JWT
}

// NewServer builds the HTTP surface over the wired services.
func NewServer(services *service.Services, tokens *auth.JWT) *Server {
	return &Server{services: services, tokens: tokens}
}

// Handler is the whole router: the route table under /api and /openapi, plus the
// WebSocket endpoints, which are mounted separately because their auth differs.
func (s *Server) Handler() http.Handler {
	router := chi.NewRouter()
	authenticator := &Authenticator{Tokens: jwtParser{signer: s.tokens}, Users: userLookup{users: s.services.Users}}
	Mount(router, authenticator, s.Routes(), func(api chi.Router) {})
	s.mountWebSockets(router, authenticator)
	return router
}

// ---------------------------------------------------------------------------
// request helpers

// intQuery reads an integer query parameter, falling back to fallback when it
// is absent or unparseable — the Java @RequestParam(defaultValue) behaviour.
func intQuery(r *http.Request, name string, fallback int) int {
	value, err := strconv.Atoi(Query(r, name))
	if err != nil {
		return fallback
	}
	return value
}

// boolQuery reads a boolean query parameter, defaulting to false.
func boolQuery(r *http.Request, name string) bool {
	return Query(r, name) == "true"
}

// environmentOf reads the `environment` query parameter every cluster-touching
// endpoint takes. The name is always `environment`, never `env`.
func environmentOf(r *http.Request) string { return Query(r, "environment") }

// callerOf returns the authenticated caller's user id.
func callerOf(r *http.Request) string { return CallerID(r.Context()) }

// ---------------------------------------------------------------------------
// auth adapters
//
// The middleware is written against two narrow interfaces so it can be tested
// without a database or a signing key; these adapt the real implementations.

type jwtParser struct{ signer *auth.JWT }

func (p jwtParser) ParseToken(token string) (TokenClaims, bool) {
	claims, err := p.signer.Parse(token)
	if err != nil {
		return TokenClaims{}, false
	}
	return TokenClaims{UserID: claims.UserID, Username: claims.Username, Role: claims.Role}, true
}

type userLookup struct{ users *service.UserService }

func (l userLookup) FindUserByID(ctx context.Context, id string) (*domain.User, error) {
	return l.users.FindByID(ctx, id)
}

func (l userLookup) FindUserByUsername(ctx context.Context, username string) (*domain.User, error) {
	return l.users.FindByUsername(ctx, username)
}

func (l userLookup) FindUserByAccessToken(ctx context.Context, token string) (*domain.User, error) {
	return l.users.FindByAccessToken(ctx, token)
}
