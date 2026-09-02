package domain

import (
	"crypto/rand"
)

const idAlphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
const idLength = 24

// NewID returns a 24-character NanoId over [a-z0-9], the identity format of
// every persisted row and of access tokens (prefixed with "sk-oops-").
func NewID() string {
	buffer := make([]byte, idLength)
	out := make([]byte, 0, idLength)
	for len(out) < idLength {
		if _, err := rand.Read(buffer); err != nil {
			panic("crypto/rand unavailable: " + err.Error())
		}
		for _, b := range buffer {
			// mask to 6 bits (0..63) and reject values >= 36 to stay uniform
			index := int(b & 63)
			if index < len(idAlphabet) {
				out = append(out, idAlphabet[index])
				if len(out) == idLength {
					break
				}
			}
		}
	}
	return string(out)
}

// AccessTokenPrefix is prepended to a NanoId to form an OpenAPI access token.
const AccessTokenPrefix = "sk-oops-"

// NewAccessToken mints a fresh OpenAPI access token.
func NewAccessToken() string { return AccessTokenPrefix + NewID() }
