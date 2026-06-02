package com.github.wellch4n.oops.shared.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EncryptionUtilsTests {

    @BeforeEach
    void setUp() {
        EncryptionUtils.setSecretKey("test-secret-key-for-unit-tests-32");
    }

    @Test
    void encryptAndDecryptRoundTrip() {
        String plaintext = "my-k8s-token";
        String encrypted = EncryptionUtils.encrypt(plaintext);
        assertEquals(plaintext, EncryptionUtils.decrypt(encrypted));
    }

    @Test
    void encryptNullReturnsNull() {
        assertNull(EncryptionUtils.encrypt(null));
    }

    @Test
    void decryptNullReturnsNull() {
        assertNull(EncryptionUtils.decrypt(null));
    }

    @Test
    void encryptWithNoKeyReturnsPlaintext() {
        EncryptionUtils.setSecretKey(null);
        assertEquals("plain", EncryptionUtils.encrypt("plain"));
    }

    @Test
    void decryptWithNoKeyReturnsInput() {
        EncryptionUtils.setSecretKey("");
        assertEquals("cipher", EncryptionUtils.decrypt("cipher"));
    }
}
