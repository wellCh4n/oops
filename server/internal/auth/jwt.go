// Package auth issues and verifies the UI session JWTs. The token shape and
// the algorithm selection follow the Java JwtUtils (jjwt 0.12.6) so tokens
// issued by either backend verify in the other.
package auth

import (
	"errors"
	"fmt"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// Claims is the identity carried by a session token.
type Claims struct {
	UserID   string
	Username string
	Role     string
}

// JWT signs and parses session tokens with an HMAC key.
type JWT struct {
	secret     []byte
	expiration time.Duration
	method     *jwt.SigningMethodHMAC
}

// NewJWT builds a signer. Like jjwt's Keys.hmacShaKeyFor, the algorithm is
// chosen by key length: < 48 bytes HS256, 48–63 HS384, >= 64 HS512.
func NewJWT(secret string, expiration time.Duration) *JWT {
	keyBytes := []byte(secret)
	method := jwt.SigningMethodHS256
	switch {
	case len(keyBytes) >= 64:
		method = jwt.SigningMethodHS512
	case len(keyBytes) >= 48:
		method = jwt.SigningMethodHS384
	}
	return &JWT{secret: keyBytes, expiration: expiration, method: method}
}

// Generate issues a compact JWS with claims sub=username, userId, role, iat, exp.
func (signer *JWT) Generate(userID, username, role string) (string, error) {
	now := time.Now()
	claims := jwt.MapClaims{
		"sub":    username,
		"userId": userID,
		"role":   role,
		"iat":    now.Unix(),
		"exp":    now.Add(signer.expiration).Unix(),
	}
	token, err := jwt.NewWithClaims(signer.method, claims).SignedString(signer.secret)
	if err != nil {
		return "", fmt.Errorf("sign jwt: %w", err)
	}
	return token, nil
}

var acceptedMethods = []string{
	jwt.SigningMethodHS256.Alg(),
	jwt.SigningMethodHS384.Alg(),
	jwt.SigningMethodHS512.Alg(),
}

// ErrInvalidToken is returned for any token that fails signature or expiry
// checks, or lacks a subject.
var ErrInvalidToken = errors.New("invalid token")

// Parse verifies the signature (any HMAC-SHA algorithm) and the expiry, and
// returns the identity claims. userId may be absent on legacy tokens; callers
// fall back to a lookup by username in that case.
func (signer *JWT) Parse(tokenString string) (*Claims, error) {
	claims := jwt.MapClaims{}
	_, err := jwt.ParseWithClaims(tokenString, claims, func(*jwt.Token) (any, error) {
		return signer.secret, nil
	}, jwt.WithValidMethods(acceptedMethods), jwt.WithExpirationRequired())
	if err != nil {
		return nil, fmt.Errorf("%w: %w", ErrInvalidToken, err)
	}
	username, _ := claims["sub"].(string)
	if username == "" {
		return nil, fmt.Errorf("%w: missing subject", ErrInvalidToken)
	}
	userID, _ := claims["userId"].(string)
	role, _ := claims["role"].(string)
	return &Claims{UserID: userID, Username: username, Role: role}, nil
}

// IsValid reports whether the token parses and has not expired.
func (signer *JWT) IsValid(tokenString string) bool {
	_, err := signer.Parse(tokenString)
	return err == nil
}
