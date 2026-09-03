package httpapi

import (
	"net/http"

	"github.com/wellch4n/oops/server/internal/domain"
	"github.com/wellch4n/oops/server/internal/service"
)

// ---------------------------------------------------------------------------
// auth, features, health

func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	var request service.LoginCommand
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	view, err := s.services.Login(r.Context(), request, s.tokens.Generate)
	Respond(w, r, view, err)
}

func (s *Server) features(w http.ResponseWriter, r *http.Request) {
	OK(w, s.services.Features())
}

func (s *Server) health(w http.ResponseWriter, r *http.Request) { OK(w, "ok") }

// externalProviders lists the OAuth providers that are actually configured, so
// the login page only offers buttons that lead somewhere.
func (s *Server) externalProviders(w http.ResponseWriter, r *http.Request) {
	OK(w, EmptyIfNil(s.services.ExternalAuth.EnabledProviders()))
}

// externalLoginURL starts the flow. It answers with the provider's authorize
// URL inside the ordinary envelope rather than issuing an HTTP redirect: the
// caller is the login page's fetch, not the browser's address bar, and a 302 on
// an XHR would be followed silently and land as an opaque CORS failure.
func (s *Server) externalLoginURL(w http.ResponseWriter, r *http.Request) {
	url, err := s.services.ExternalAuth.LoginURL(Param(r, "provider"))
	Respond(w, r, url, err)
}

// externalCallback completes the flow, exchanging the provider's one-time code
// for an OOPS session token.
func (s *Server) externalCallback(w http.ResponseWriter, r *http.Request) {
	code := Query(r, "code")
	if code == "" {
		// Also accept it in the body, which is what a POST from the callback
		// page naturally sends.
		var request struct {
			Code string `json:"code"`
		}
		_ = DecodeJSON(r, &request)
		code = request.Code
	}
	token, err := s.services.ExternalAuth.Authenticate(r.Context(), Param(r, "provider"), code, s.tokens.Generate)
	Respond(w, r, token, err)
}

// ---------------------------------------------------------------------------
// users

func (s *Server) listUsers(w http.ResponseWriter, r *http.Request) {
	users, err := s.services.Users.List(r.Context())
	Respond(w, r, EmptyIfNil(users), err)
}

func (s *Server) listUsersPage(w http.ResponseWriter, r *http.Request) {
	page, err := s.services.Users.ListPage(r.Context(), Query(r, "keyword"), intQuery(r, "page", 1), intQuery(r, "size", 10))
	Respond(w, r, Page[domain.User]{Total: page.Total, Data: EmptyIfNil(page.Data), Size: page.Size, TotalPages: page.TotalPages}, err)
}

func (s *Server) currentUser(w http.ResponseWriter, r *http.Request) {
	user, err := s.services.Users.FindByID(r.Context(), callerOf(r))
	if err != nil {
		Error(w, r, err)
		return
	}
	if user == nil {
		Fail(w, "User not found")
		return
	}
	OK(w, user)
}

// createUserCommand is the admin's create-account body.
type createUserCommand struct {
	Username string `json:"username"`
	Email    string `json:"email"`
	Password string `json:"password"`
}

func (s *Server) createUser(w http.ResponseWriter, r *http.Request) {
	var request createUserCommand
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	if request.Username == "" {
		Fail(w, "Username is required")
		return
	}
	if request.Email == "" {
		Fail(w, "Email is required")
		return
	}
	// An admin creates ordinary accounts; promoting one is a separate update.
	_, err := s.services.Users.Create(r.Context(), request.Username, request.Email, request.Password, domain.RoleUser)
	Respond(w, r, true, err)
}

// updateUserCommand rewrites an account. It is not a patch: an omitted role or
// email clears it.
type updateUserCommand struct {
	Role     *domain.UserRole `json:"role"`
	Email    string           `json:"email"`
	Password string           `json:"password"`
	Enabled  *bool            `json:"enabled"`
}

func (s *Server) updateUser(w http.ResponseWriter, r *http.Request) {
	var request updateUserCommand
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	err := s.services.Users.Update(r.Context(), Param(r, "id"), request.Role, request.Email, request.Password, request.Enabled)
	Respond(w, r, true, err)
}

func (s *Server) deleteUser(w http.ResponseWriter, r *http.Request) {
	Respond(w, r, true, s.services.Users.Delete(r.Context(), Param(r, "id")))
}

func (s *Server) updateMyProfile(w http.ResponseWriter, r *http.Request) {
	var request struct {
		Email string `json:"email"`
	}
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	Respond(w, r, true, s.services.Users.UpdateMyProfile(r.Context(), callerOf(r), request.Email))
}

func (s *Server) changeMyPassword(w http.ResponseWriter, r *http.Request) {
	var request struct {
		OldPassword string `json:"oldPassword"`
		NewPassword string `json:"newPassword"`
	}
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	Respond(w, r, true, s.services.Users.ChangeMyPassword(r.Context(), callerOf(r), request.OldPassword, request.NewPassword))
}

func (s *Server) resetMyAccessToken(w http.ResponseWriter, r *http.Request) {
	token, err := s.services.Users.ResetMyAccessToken(r.Context(), callerOf(r))
	Respond(w, r, token, err)
}

// ---------------------------------------------------------------------------
// namespaces

func (s *Server) listNamespaces(w http.ResponseWriter, r *http.Request) {
	namespaces, err := s.services.Namespaces.List(r.Context())
	Respond(w, r, EmptyIfNil(namespaces), err)
}

func (s *Server) createNamespace(w http.ResponseWriter, r *http.Request) {
	var request domain.Namespace
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	err := s.services.Namespaces.Create(r.Context(), domain.Deref(request.Name), domain.Deref(request.Description))
	Respond(w, r, true, err)
}

func (s *Server) updateNamespace(w http.ResponseWriter, r *http.Request) {
	var request domain.Namespace
	if err := DecodeJSON(r, &request); err != nil {
		Error(w, r, err)
		return
	}
	err := s.services.Namespaces.Update(r.Context(), domain.Deref(request.Name), domain.Deref(request.Description))
	Respond(w, r, true, err)
}

// ---------------------------------------------------------------------------
// cron preview

func (s *Server) nextCronRuns(w http.ResponseWriter, r *http.Request) {
	runs, err := service.NextCronRuns(Query(r, "expression"), intQuery(r, "count", 1))
	Respond(w, r, runs, err)
}
