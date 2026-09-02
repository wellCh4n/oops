package httpapi

import (
	"net/http"
	"regexp"
	"sort"
	"strings"

	"github.com/go-chi/chi/v5"
	"github.com/wellch4n/oops/server/internal/domain"
)

// Route is one endpoint of the table. Pattern is relative to the /api or
// /openapi prefix and uses chi's {param} syntax.
type Route struct {
	Method     string
	Pattern    string
	Handler    http.HandlerFunc
	Controller string // Java controller name, kept for the coverage report
	Admin      bool   // @PreAuthorize("hasRole('ADMIN')")
	OpenAPI    bool   // also mounted under /openapi
	Hidden     bool   // @OpenApiHidden: 405 under /openapi
	Public     bool   // no authentication (login, health, features, external auth)
}

// RouteInfo is the JSON shape tests/integration/routes.json uses.
type RouteInfo struct {
	Method     string `json:"method"`
	Path       string `json:"path"`
	Key        string `json:"key"`
	Controller string `json:"controller"`
}

var pathVariable = regexp.MustCompile(`\{[^}]+\}`)

// excludedFromCoverage are the two browser-driven endpoints the suite skips.
var excludedFromCoverage = map[string]bool{
	"/api/auth/external/{provider}/redirect": true,
	"/api/auth/external/{provider}/callback": true,
}

// RouteTable renders the /api routes in extract_routes.py's format, sorted by key.
func RouteTable(routes []Route) []RouteInfo {
	unique := map[string]RouteInfo{}
	for _, route := range routes {
		path := "/api" + route.Pattern
		if excludedFromCoverage[path] {
			continue
		}
		key := route.Method + " " + pathVariable.ReplaceAllString(path, "{}")
		if _, exists := unique[key]; !exists {
			unique[key] = RouteInfo{Method: route.Method, Path: path, Key: key, Controller: route.Controller}
		}
	}
	out := make([]RouteInfo, 0, len(unique))
	for _, info := range unique {
		out = append(out, info)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Key < out[j].Key })
	return out
}

// Mount registers the table under /api (JWT) and /openapi (access token).
// Anything unmatched under either prefix answers the Java way: HTTP 200 with
// "Internal server error" for an authenticated caller.
func Mount(router chi.Router, auth *Authenticator, routes []Route, extra func(api chi.Router)) {
	unmatched := func(w http.ResponseWriter, r *http.Request) {
		Fail(w, domain.InternalServerErrorMessage)
	}
	// Public routes are mounted outside the auth group.
	for _, route := range routes {
		if route.Public {
			router.Method(route.Method, "/api"+route.Pattern, route.Handler)
		}
	}
	router.Group(func(api chi.Router) {
		api.Use(auth.RequireJWT)
		api.NotFound(unmatched)
		api.MethodNotAllowed(unmatched)
		for _, route := range routes {
			if route.Public {
				continue
			}
			handler := route.Handler
			if route.Admin {
				handler = RequireAdmin(handler)
			}
			api.Method(route.Method, "/api"+route.Pattern, handler)
		}
		if extra != nil {
			extra(api)
		}
	})
	router.Group(func(open chi.Router) {
		open.Use(auth.RequireAccessToken)
		open.NotFound(unmatched)
		open.MethodNotAllowed(unmatched)
		for _, route := range routes {
			if !route.OpenAPI {
				continue
			}
			handler := route.Handler
			if route.Hidden {
				handler = HiddenOnOpenAPI
			} else if route.Admin {
				handler = RequireAdmin(handler)
			}
			open.Method(route.Method, "/openapi"+route.Pattern, handler)
		}
	})
}

// Param returns a URL path parameter.
func Param(r *http.Request, name string) string { return chi.URLParam(r, name) }

// Query returns a trimmed query parameter.
func Query(r *http.Request, name string) string { return strings.TrimSpace(r.URL.Query().Get(name)) }
