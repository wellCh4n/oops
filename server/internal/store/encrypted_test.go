package store

import (
	"reflect"
	"strings"
	"testing"

	"github.com/wellch4n/oops/server/internal/crypto"
)

// secretColumns is every column the Java backend carried an encrypting
// converter on. It is written out here rather than derived, because the whole
// point is that adding a secret column should require saying so.
var secretColumns = map[string]string{
	"environment.api_server_token":          "the cluster credential",
	"environment.image_repository_password": "the registry password",
	"environment.git_credential":            "the git username, password and private key",
	"domain.cert_pem":                       "an uploaded TLS certificate",
	"domain.key_pem":                        "an uploaded TLS private key",
}

// The first attempt encrypted at each call site and silently missed
// domain.cert_pem and domain.key_pem, which put a TLS private key in the
// database in the clear. Declaring the column type is what makes that
// impossible now, so this checks the declarations.
func TestSecretColumnsAreDeclaredEncrypted(t *testing.T) {
	found := map[string]bool{}
	for table, row := range rowStructs {
		rowType := reflect.TypeOf(row)
		for index := range rowType.NumField() {
			field := rowType.Field(index)
			column := table + "." + strings.Split(field.Tag.Get("db"), ",")[0]
			encrypting := strings.HasPrefix(field.Type.Name(), "Encrypted")
			_, secret := secretColumns[column]
			switch {
			case secret && !encrypting:
				t.Errorf("%s holds %s and must be an encrypted column, not %s",
					column, secretColumns[column], field.Type)
			case !secret && encrypting:
				t.Errorf("%s is encrypted but is not listed as a secret column; "+
					"add it to secretColumns so the reason is written down", column)
			case secret:
				found[column] = true
			}
		}
	}
	for column := range secretColumns {
		if !found[column] {
			t.Errorf("%s is listed as a secret column but no row struct declares it", column)
		}
	}
}

// Encrypting has to be something the caller cannot observe: what goes in comes
// back out, and what reaches the database is not what went in.
func TestEncryptedRoundTripsAndDoesNotStorePlaintext(t *testing.T) {
	crypto.SetDefault(crypto.NewCodec("a-key-long-enough-for-the-cipher"))
	t.Cleanup(func() { crypto.SetDefault(crypto.NewCodec("")) })

	const secret = "-----BEGIN PRIVATE KEY-----\nsensitive\n-----END PRIVATE KEY-----"
	stored, err := Encrypted{String: secret, Valid: true}.Value()
	if err != nil {
		t.Fatal(err)
	}
	if cipher, _ := stored.(string); cipher == secret || strings.Contains(cipher, "PRIVATE KEY") {
		t.Fatalf("the plaintext reached the database: %q", cipher)
	}
	var read Encrypted
	if err := read.Scan(stored); err != nil {
		t.Fatal(err)
	}
	if read.String != secret {
		t.Fatalf("round trip returned %q", read.String)
	}
}

// A column written before a key was configured still has to be readable after
// one is, or turning encryption on would orphan every existing row. The Java
// converter fell back the same way.
func TestUndecryptableColumnIsReadAsPlaintext(t *testing.T) {
	crypto.SetDefault(crypto.NewCodec("a-key-long-enough-for-the-cipher"))
	t.Cleanup(func() { crypto.SetDefault(crypto.NewCodec("")) })

	var read Encrypted
	if err := read.Scan("this was written before there was a key"); err != nil {
		t.Fatal(err)
	}
	if read.String != "this was written before there was a key" {
		t.Fatalf("a plaintext column did not survive the read: %q", read.String)
	}
}

// NULL has to stay NULL: an environment with no git credential must not gain an
// empty encrypted blob.
func TestNullEncryptedColumnStaysNull(t *testing.T) {
	value, err := Encrypted{}.Value()
	if err != nil || value != nil {
		t.Fatalf("an unset encrypted column became %v (%v)", value, err)
	}
	var read Encrypted
	if err := read.Scan(nil); err != nil || read.Valid {
		t.Fatalf("a NULL column scanned as %+v (%v)", read, err)
	}
}
