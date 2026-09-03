package store

import (
	"database/sql/driver"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/wellch4n/oops/server/internal/crypto"
)

// Encrypted is a column that is AES-encrypted at rest.
//
// It is the Go equivalent of the Java backend's
// @Convert(converter = EncryptedStringConverter.class): the database driver
// calls Value on the way in and Scan on the way out, so declaring a row field
// as Encrypted is the whole of it. No repository can write one of these columns
// in the clear, because there is no code path that reaches the column without
// passing through this type.
//
// That is the point of doing it this way rather than encrypting at each call
// site. The call-site version was written first and silently missed
// domain.cert_pem and domain.key_pem — a TLS private key in plaintext, and
// unreadable rows on any database written by 2.x.
type Encrypted struct {
	// String is the plaintext. What reaches the database is the ciphertext.
	String string
	Valid  bool
}

// EncryptedOf wraps a plaintext pointer; nil becomes SQL NULL.
func EncryptedOf(plain *string) Encrypted {
	if plain == nil {
		return Encrypted{}
	}
	return Encrypted{String: *plain, Valid: true}
}

// Ptr returns the plaintext, or nil for a NULL column.
func (e Encrypted) Ptr() *string {
	if !e.Valid {
		return nil
	}
	plain := e.String
	return &plain
}

// Value encrypts on the way to the database.
func (e Encrypted) Value() (driver.Value, error) {
	if !e.Valid {
		return nil, nil
	}
	cipher, err := crypto.EncryptValue(e.String)
	if err != nil {
		return nil, err
	}
	return cipher, nil
}

// Scan decrypts on the way out. A value that does not decrypt is kept as it
// stands, so a column written before a key was configured stays readable.
func (e *Encrypted) Scan(src any) error {
	switch value := src.(type) {
	case nil:
		*e = Encrypted{}
		return nil
	case string:
		*e = Encrypted{String: crypto.DecryptValue(value), Valid: true}
		return nil
	case []byte:
		*e = Encrypted{String: crypto.DecryptValue(string(value)), Valid: true}
		return nil
	default:
		return fmt.Errorf("cannot scan %T into an encrypted column", src)
	}
}

// EncryptedJSON is a JSON blob that is encrypted at rest — serialised first,
// then encrypted as one string, which is the order the Java
// GitCredentialConverter used and therefore the only order that can read the
// rows it wrote.
type EncryptedJSON[T any] struct {
	Payload *T
}

// Value serialises then encrypts; a nil payload is SQL NULL.
func (e EncryptedJSON[T]) Value() (driver.Value, error) {
	if e.Payload == nil {
		return nil, nil
	}
	plain, err := json.Marshal(e.Payload)
	if err != nil {
		return nil, err
	}
	return Encrypted{String: string(plain), Valid: true}.Value()
}

// Scan decrypts then deserialises.
func (e *EncryptedJSON[T]) Scan(src any) error {
	var column Encrypted
	if err := column.Scan(src); err != nil {
		return err
	}
	if !column.Valid || strings.TrimSpace(column.String) == "" {
		e.Payload = nil
		return nil
	}
	var decoded T
	if err := json.Unmarshal([]byte(column.String), &decoded); err != nil {
		return fmt.Errorf("malformed encrypted JSON: %w", err)
	}
	e.Payload = &decoded
	return nil
}
