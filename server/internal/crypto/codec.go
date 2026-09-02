// Package crypto holds the two byte-compatible primitives the Java backend
// used for secrets at rest: AES-256-GCM for environment Kubernetes tokens
// (EncryptionUtils) and bcrypt for user passwords (Spring's BCryptPasswordEncoder).
package crypto

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"

	"golang.org/x/crypto/bcrypt"
)

const gcmNonceLength = 12

// Codec encrypts and decrypts strings exactly like the Java EncryptionUtils:
// key = SHA-256(secret UTF-8), AES-256-GCM with a random 12-byte IV and a
// 128-bit tag, no additional data, output = standard padded Base64 of
// iv || ciphertext || tag. A blank secret turns both operations into
// pass-throughs so an installation that never configured a key keeps working.
type Codec struct {
	aead cipher.AEAD
}

// NewCodec derives the AES key from secretKey. A blank key yields a
// pass-through codec.
func NewCodec(secretKey string) *Codec {
	if strings.TrimSpace(secretKey) == "" {
		return &Codec{}
	}
	digest := sha256.Sum256([]byte(secretKey))
	block, err := aes.NewCipher(digest[:])
	if err != nil {
		// A 32-byte key can never be rejected by aes.NewCipher.
		panic("crypto: aes.NewCipher rejected a SHA-256 key: " + err.Error())
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		panic("crypto: cipher.NewGCM failed: " + err.Error())
	}
	return &Codec{aead: aead}
}

// Enabled reports whether a key is configured (false means pass-through).
func (codec *Codec) Enabled() bool { return codec.aead != nil }

// Encrypt returns the Base64 envelope for plain, or plain itself when no key
// is configured.
func (codec *Codec) Encrypt(plain string) (string, error) {
	if codec.aead == nil {
		return plain, nil
	}
	nonce := make([]byte, gcmNonceLength)
	if _, err := rand.Read(nonce); err != nil {
		return "", fmt.Errorf("encryption failed: %w", err)
	}
	sealed := codec.aead.Seal(nil, nonce, []byte(plain), nil)
	envelope := make([]byte, 0, len(nonce)+len(sealed))
	envelope = append(envelope, nonce...)
	envelope = append(envelope, sealed...)
	return base64.StdEncoding.EncodeToString(envelope), nil
}

// Decrypt reverses Encrypt. Any malformed input or authentication failure is
// reported as an error wrapping ErrDecryptionFailed; callers decide whether to
// fall back to the raw value.
func (codec *Codec) Decrypt(ciphertext string) (string, error) {
	if codec.aead == nil {
		return ciphertext, nil
	}
	envelope, err := base64.StdEncoding.DecodeString(ciphertext)
	if err != nil {
		return "", fmt.Errorf("%w: %w", ErrDecryptionFailed, err)
	}
	if len(envelope) < gcmNonceLength+codec.aead.Overhead() {
		return "", fmt.Errorf("%w: envelope too short", ErrDecryptionFailed)
	}
	nonce, sealed := envelope[:gcmNonceLength], envelope[gcmNonceLength:]
	plain, err := codec.aead.Open(nil, nonce, sealed, nil)
	if err != nil {
		return "", fmt.Errorf("%w: %w", ErrDecryptionFailed, err)
	}
	return string(plain), nil
}

// ErrDecryptionFailed mirrors Java's RuntimeException("Decryption failed").
var ErrDecryptionFailed = errors.New("Decryption failed")

// bcryptCost matches Spring Security's BCryptPasswordEncoder default strength.
const bcryptCost = 10

// HashPassword returns a bcrypt hash with the "$2a$" prefix and cost 10, the
// format Spring Security wrote, so hashes created by either backend verify
// in the other.
func HashPassword(raw string) (string, error) {
	hash, err := bcrypt.GenerateFromPassword([]byte(raw), bcryptCost)
	if err != nil {
		return "", fmt.Errorf("hash password: %w", err)
	}
	return string(hash), nil
}

// CheckPassword reports whether raw matches the stored bcrypt hash. An empty
// hash (auto-provisioned Feishu users have no password) never matches.
func CheckPassword(raw, hash string) bool {
	if hash == "" {
		return false
	}
	return bcrypt.CompareHashAndPassword([]byte(hash), []byte(raw)) == nil
}
