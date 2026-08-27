package httpapi

import (
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"

	"github.com/wellch4n/oops/go-backend/internal/config"
)

func testServer() *Server {
	cfg := &config.Config{}
	cfg.Oops.JWT.Secret = "0123456789abcdef0123456789abcdef"
	cfg.Oops.JWT.Expiration = 604800000
	return &Server{cfg: cfg}
}

func TestJWTRoundTrip(t *testing.T) {
	server := testServer()
	token, err := server.signJWT("user-nanoid-24-chars-xxxx", "admin", "ADMIN")
	if err != nil {
		t.Fatal(err)
	}
	principal, err := server.parseJWT(token)
	if err != nil {
		t.Fatal(err)
	}
	if principal.UserID != "user-nanoid-24-chars-xxxx" || principal.Username != "admin" || principal.Role != "ADMIN" {
		t.Fatalf("unexpected principal: %+v", principal)
	}
}

// The payload must carry exactly the claims JwtUtils reads: sub, userId, role.
func TestJWTClaimNamesMatchJavaSide(t *testing.T) {
	server := testServer()
	token, err := server.signJWT("uid", "alice", "USER")
	if err != nil {
		t.Fatal(err)
	}
	payloadSegment := strings.Split(token, ".")[1]
	payloadBytes, err := base64.RawURLEncoding.DecodeString(payloadSegment)
	if err != nil {
		t.Fatal(err)
	}
	claims := map[string]any{}
	if err := json.Unmarshal(payloadBytes, &claims); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"sub", "userId", "role", "iat", "exp"} {
		if _, found := claims[name]; !found {
			t.Fatalf("claim %q missing from payload: %v", name, claims)
		}
	}
	if claims["sub"] != "alice" || claims["userId"] != "uid" || claims["role"] != "USER" {
		t.Fatalf("unexpected claim values: %v", claims)
	}
}

func TestParseRejectsTamperedToken(t *testing.T) {
	server := testServer()
	token, _ := server.signJWT("uid", "alice", "USER")
	if _, err := server.parseJWT(token + "x"); err == nil {
		t.Fatal("expected tampered token to be rejected")
	}
}
