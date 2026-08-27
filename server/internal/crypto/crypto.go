// Package crypto mirrors shared/util/EncryptionUtils: AES-256-GCM with the key
// derived as SHA-256(secret) and the payload encoded base64(iv || ciphertext).
package crypto

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
)

const gcmIVLength = 12

type Codec struct {
	secret string
}

func NewCodec(secret string) *Codec {
	return &Codec{secret: secret}
}

func (c *Codec) gcm() (cipher.AEAD, error) {
	key := sha256.Sum256([]byte(c.secret))
	block, err := aes.NewCipher(key[:])
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}

// Decrypt matches EncryptionUtils.decrypt; with no secret configured the
// value passes through unchanged, exactly like the Java side.
func (c *Codec) Decrypt(ciphertext string) (string, error) {
	if ciphertext == "" || c.secret == "" {
		return ciphertext, nil
	}
	decoded, err := base64.StdEncoding.DecodeString(ciphertext)
	if err != nil {
		return "", fmt.Errorf("decode ciphertext: %w", err)
	}
	if len(decoded) < gcmIVLength {
		return "", fmt.Errorf("ciphertext shorter than IV")
	}
	aead, err := c.gcm()
	if err != nil {
		return "", err
	}
	plaintext, err := aead.Open(nil, decoded[:gcmIVLength], decoded[gcmIVLength:], nil)
	if err != nil {
		return "", fmt.Errorf("decrypt: %w", err)
	}
	return string(plaintext), nil
}

func (c *Codec) Encrypt(plaintext string) (string, error) {
	if plaintext == "" || c.secret == "" {
		return plaintext, nil
	}
	aead, err := c.gcm()
	if err != nil {
		return "", err
	}
	iv := make([]byte, gcmIVLength)
	if _, err := rand.Read(iv); err != nil {
		return "", err
	}
	sealed := aead.Seal(iv, iv, []byte(plaintext), nil)
	return base64.StdEncoding.EncodeToString(sealed), nil
}
