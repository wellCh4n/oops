package auth

import (
	"strings"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

const secret32 = "0123456789abcdef0123456789abcdef"

func TestGenerateAndParse(t *testing.T) {
	signer := NewJWT(secret32, time.Hour)
	token, err := signer.Generate("user-id-1", "alice", "ADMIN")
	if err != nil {
		t.Fatal(err)
	}
	claims, err := signer.Parse(token)
	if err != nil {
		t.Fatal(err)
	}
	if claims.UserID != "user-id-1" || claims.Username != "alice" || claims.Role != "ADMIN" {
		t.Fatalf("unexpected claims %+v", claims)
	}
	other := NewJWT(secret32, time.Minute)
	if _, err := other.Parse(token); err != nil {
		t.Fatalf("a second instance with the same secret must verify: %v", err)
	}
	wrong := NewJWT("another-secret-of-32-characters!", time.Hour)
	if _, err := wrong.Parse(token); err == nil {
		t.Fatal("different secret must reject")
	}
}

func TestAlgorithmByKeyLength(t *testing.T) {
	cases := map[int]string{32: "HS256", 47: "HS256", 48: "HS384", 63: "HS384", 64: "HS512", 100: "HS512"}
	for length, alg := range cases {
		signer := NewJWT(strings.Repeat("k", length), time.Hour)
		token, err := signer.Generate("u", "n", "USER")
		if err != nil {
			t.Fatal(err)
		}
		parsed, _, err := jwt.NewParser().ParseUnverified(token, jwt.MapClaims{})
		if err != nil {
			t.Fatal(err)
		}
		if parsed.Method.Alg() != alg {
			t.Fatalf("length %d: got %s want %s", length, parsed.Method.Alg(), alg)
		}
		if _, err := signer.Parse(token); err != nil {
			t.Fatalf("length %d: parse failed: %v", length, err)
		}
	}
}

func TestExpiredTokenRejected(t *testing.T) {
	signer := NewJWT(secret32, -time.Minute)
	token, err := signer.Generate("u", "n", "USER")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := signer.Parse(token); err == nil {
		t.Fatal("expired token must be rejected")
	}
	if signer.IsValid(token) {
		t.Fatal("IsValid must be false for an expired token")
	}
}

func TestGarbageRejected(t *testing.T) {
	signer := NewJWT(secret32, time.Hour)
	if _, err := signer.Parse("not.a.jwt"); err == nil {
		t.Fatal("garbage must be rejected")
	}
}
