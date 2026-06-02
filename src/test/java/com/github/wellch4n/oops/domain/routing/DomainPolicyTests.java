package com.github.wellch4n.oops.domain.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DomainPolicyTests {

    private final DomainPolicy policy = new DomainPolicy();

    @Test
    void normalizeHostStripsWildcard() {
        assertEquals("example.com", policy.normalizeHost("*.example.com"));
    }

    @Test
    void normalizeHostTrimsAndLowercases() {
        assertEquals("example.com", policy.normalizeHost("  EXAMPLE.COM  "));
    }

    @Test
    void normalizeHostNullReturnsEmpty() {
        assertEquals("", policy.normalizeHost(null));
    }

    @Test
    void validateHostAcceptsValidDomain() {
        policy.validateHost("example.com");
        policy.validateHost("sub.example.com");
        policy.validateHost("my-app.example.com");
    }

    @Test
    void validateHostRejectsBlank() {
        assertThrows(BizException.class, () -> policy.validateHost(null));
        assertThrows(BizException.class, () -> policy.validateHost(""));
        assertThrows(BizException.class, () -> policy.validateHost("  "));
    }

    @Test
    void validateHostRejectsInvalidFormat() {
        assertThrows(BizException.class, () -> policy.validateHost("not_valid"));
        assertThrows(BizException.class, () -> policy.validateHost("-bad.com"));
        assertThrows(BizException.class, () -> policy.validateHost("localhost"));
    }

    @Test
    void findBestMatchReturnsLongestSuffix() {
        List<String> candidates = List.of("example.com", "sub.example.com");
        Optional<String> result = policy.findBestMatch("app.sub.example.com", candidates, s -> s);
        assertTrue(result.isPresent());
        assertEquals("sub.example.com", result.get());
    }

    @Test
    void findBestMatchExactMatch() {
        List<String> candidates = List.of("example.com");
        Optional<String> result = policy.findBestMatch("example.com", candidates, s -> s);
        assertTrue(result.isPresent());
        assertEquals("example.com", result.get());
    }

    @Test
    void findBestMatchReturnsEmptyWhenNoMatch() {
        List<String> candidates = List.of("other.com");
        Optional<String> result = policy.findBestMatch("example.com", candidates, s -> s);
        assertFalse(result.isPresent());
    }

    @Test
    void findBestMatchHandlesNullAndEmptyInputs() {
        assertFalse(policy.findBestMatch(null, List.of("example.com"), s -> s).isPresent());
        assertFalse(policy.<String>findBestMatch("example.com", null, s -> s).isPresent());
        assertFalse(policy.findBestMatch("example.com", List.<String>of(), s -> s).isPresent());
    }
}
