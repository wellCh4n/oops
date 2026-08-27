package httpapi

import (
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

// Principal mirrors AuthUserPrincipal on the Java side.
type Principal struct {
	UserID   string
	Username string
	Role     string
}

const principalKey = "oops.principal"

func principalFrom(c *gin.Context) Principal {
	principal, _ := c.Value(principalKey).(Principal)
	return principal
}

// signJWT produces a token with the exact claim set JwtUtils emits
// (sub, userId, role, iat, exp) so both backends accept each other's tokens.
func (s *Server) signJWT(userID, username, role string) (string, error) {
	now := time.Now()
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		"sub":    username,
		"userId": userID,
		"role":   role,
		"iat":    now.Unix(),
		"exp":    now.Add(time.Duration(s.cfg.Oops.JWT.Expiration) * time.Millisecond).Unix(),
	})
	return token.SignedString([]byte(s.cfg.Oops.JWT.Secret))
}

func (s *Server) parseJWT(raw string) (Principal, error) {
	claims := jwt.MapClaims{}
	_, err := jwt.ParseWithClaims(raw, claims, func(t *jwt.Token) (any, error) {
		return []byte(s.cfg.Oops.JWT.Secret), nil
	}, jwt.WithValidMethods([]string{"HS256"}), jwt.WithExpirationRequired())
	if err != nil {
		return Principal{}, err
	}
	principal := Principal{}
	principal.Username, _ = claims["sub"].(string)
	principal.UserID, _ = claims["userId"].(string)
	principal.Role, _ = claims["role"].(string)
	return principal, nil
}

// requireAuth mirrors JwtAuthFilter: Bearer header, with ?token= fallback for
// WebSocket upgrades.
func (s *Server) requireAuth() gin.HandlerFunc {
	return func(c *gin.Context) {
		raw := strings.TrimPrefix(c.GetHeader("Authorization"), "Bearer ")
		if raw == "" {
			raw = c.Query("token")
		}
		principal, err := s.parseJWT(raw)
		if err != nil {
			c.AbortWithStatusJSON(http.StatusUnauthorized, fail("Unauthorized"))
			return
		}
		c.Set(principalKey, principal)
		c.Next()
	}
}
