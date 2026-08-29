package com.github.wellch4n.oops.shared.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EncryptionUtilsTests {

    @AfterEach
    void clearKey() {
        EncryptionUtils.setSecretKey(null);
    }

    @Test
    void encryptDecryptRoundTrips() {
        EncryptionUtils.setSecretKey("a-test-secret-key-value");
        String plaintext = "sensitive-k8s-token";
        String encrypted = EncryptionUtils.encrypt(plaintext);
        assertNotEquals(plaintext, encrypted);
        assertEquals(plaintext, EncryptionUtils.decrypt(encrypted));
    }

    @Test
    void encryptionUsesRandomIvSoCiphertextDiffers() {
        EncryptionUtils.setSecretKey("a-test-secret-key-value");
        String plaintext = "same-input";
        assertNotEquals(EncryptionUtils.encrypt(plaintext), EncryptionUtils.encrypt(plaintext));
    }

    @Test
    void passthroughWhenNoKeyConfigured() {
        EncryptionUtils.setSecretKey(null);
        assertEquals("plain", EncryptionUtils.encrypt("plain"));
        assertEquals("plain", EncryptionUtils.decrypt("plain"));
    }

    @Test
    void passthroughForBlankKey() {
        EncryptionUtils.setSecretKey("   ");
        assertEquals("plain", EncryptionUtils.encrypt("plain"));
    }

    @Test
    void nullInputReturnsNull() {
        EncryptionUtils.setSecretKey("a-test-secret-key-value");
        assertNull(EncryptionUtils.encrypt(null));
        assertNull(EncryptionUtils.decrypt(null));
    }
}
