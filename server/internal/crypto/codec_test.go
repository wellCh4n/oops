package crypto

import (
	"strings"
	"testing"
)

// javaReferenceCiphertext was produced by the Java EncryptionUtils algorithm
// (AES/GCM/NoPadding, SHA-256 key, 128-bit tag) with a fixed IV of
// 01..0c, secret "oops-test-secret-key" and plaintext "kubernetes-token-ABC123-✓".
// See scratchpad javaref/Ref.java; the snippet is reproduced here:
//
//	byte[] key = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(UTF_8));
//	Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
//	c.init(ENCRYPT_MODE, new SecretKeySpec(key,"AES"), new GCMParameterSpec(128, iv));
//	Base64.getEncoder().encodeToString(iv ++ c.doFinal(plain.getBytes(UTF_8)))
const (
	javaReferenceSecret     = "oops-test-secret-key"
	javaReferencePlain      = "kubernetes-token-ABC123-✓"
	javaReferenceCiphertext = "AQIDBAUGBwgJCgsMEaAIvUsXsHAZ8TlYZ4R47TxaZwqxxYPv3dh6tqgQGZbzhQVyRdKWdWRWaw=="
)

func TestDecryptJavaReferenceVector(t *testing.T) {
	codec := NewCodec(javaReferenceSecret)
	plain, err := codec.Decrypt(javaReferenceCiphertext)
	if err != nil {
		t.Fatalf("decrypt java vector: %v", err)
	}
	if plain != javaReferencePlain {
		t.Fatalf("got %q want %q", plain, javaReferencePlain)
	}
}

func TestRoundTripAndRandomIV(t *testing.T) {
	codec := NewCodec("another secret")
	if !codec.Enabled() {
		t.Fatal("codec should be enabled")
	}
	first, err := codec.Encrypt("hello 世界")
	if err != nil {
		t.Fatal(err)
	}
	second, err := codec.Encrypt("hello 世界")
	if err != nil {
		t.Fatal(err)
	}
	if first == second {
		t.Fatal("two encryptions of the same input must differ (random IV)")
	}
	for _, ciphertext := range []string{first, second} {
		plain, err := codec.Decrypt(ciphertext)
		if err != nil {
			t.Fatal(err)
		}
		if plain != "hello 世界" {
			t.Fatalf("round trip got %q", plain)
		}
	}
}

func TestBlankKeyIsPassThrough(t *testing.T) {
	codec := NewCodec("   ")
	if codec.Enabled() {
		t.Fatal("blank key must disable the codec")
	}
	out, err := codec.Encrypt("raw-token")
	if err != nil || out != "raw-token" {
		t.Fatalf("encrypt pass-through got %q, %v", out, err)
	}
	out, err = codec.Decrypt("raw-token")
	if err != nil || out != "raw-token" {
		t.Fatalf("decrypt pass-through got %q, %v", out, err)
	}
}

func TestDecryptFailures(t *testing.T) {
	codec := NewCodec("secret")
	for _, bad := range []string{"not base64!", "AAAA", javaReferenceCiphertext} {
		if _, err := codec.Decrypt(bad); err == nil {
			t.Fatalf("expected failure for %q", bad)
		} else if !strings.Contains(err.Error(), "Decryption failed") {
			t.Fatalf("unexpected error text %q", err)
		}
	}
}

func TestPasswordHashing(t *testing.T) {
	hash, err := HashPassword("admin123")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(hash, "$2a$10$") {
		t.Fatalf("hash should carry Spring's $2a$10$ prefix, got %q", hash)
	}
	if !CheckPassword("admin123", hash) {
		t.Fatal("password should verify")
	}
	if CheckPassword("wrong", hash) {
		t.Fatal("wrong password must not verify")
	}
	if CheckPassword("anything", "") {
		t.Fatal("empty hash must never verify")
	}
	// A hash produced by an independent implementation (Apache htpasswd -B,
	// cost 5, "$2y$" variant which is byte-identical to "$2a$" for this input).
	foreignHash := "$2y$05$MoBl.fIMb59pcxMomtOriOI1jFrNqHTjwtu1GxsmLqLZzsAyTtSFa"
	if !CheckPassword("admin123", foreignHash) {
		t.Fatal("hash from htpasswd should verify")
	}
	if CheckPassword("admin124", foreignHash) {
		t.Fatal("near-miss password must not verify")
	}
}
