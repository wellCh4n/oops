package crypto

import "testing"

func TestRoundTrip(t *testing.T) {
	codec := NewCodec("test-secret-key-for-unit-tests!!")
	ciphertext, err := codec.Encrypt("hello kubernetes token")
	if err != nil {
		t.Fatal(err)
	}
	plaintext, err := codec.Decrypt(ciphertext)
	if err != nil {
		t.Fatal(err)
	}
	if plaintext != "hello kubernetes token" {
		t.Fatalf("got %q", plaintext)
	}
}

func TestNoSecretPassesThrough(t *testing.T) {
	codec := NewCodec("")
	out, err := codec.Decrypt("opaque-value")
	if err != nil || out != "opaque-value" {
		t.Fatalf("out=%q err=%v", out, err)
	}
}

func TestTamperedCiphertextRejected(t *testing.T) {
	codec := NewCodec("test-secret-key-for-unit-tests!!")
	ciphertext, _ := codec.Encrypt("secret")
	if _, err := codec.Decrypt("AAAA" + ciphertext[4:]); err == nil {
		t.Fatal("expected auth failure")
	}
}
