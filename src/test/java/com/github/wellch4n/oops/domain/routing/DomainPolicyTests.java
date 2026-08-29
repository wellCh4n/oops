package com.github.wellch4n.oops.domain.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class DomainPolicyTests {

    private final DomainPolicy policy = new DomainPolicy();
    private final Function<String, String> identity = host -> host;

    @Test
    void normalizeHostStripsWildcardPrefixAndTrims() {
        assertEquals("example.com", policy.normalizeHost("  *.example.com  "));
        assertEquals("example.com", policy.normalizeHost("example.com"));
    }

    @Test
    void normalizeHostHandlesNull() {
        assertEquals("", policy.normalizeHost(null));
    }

    @Test
    void validateHostRejectsBlank() {
        assertThrows(BizException.class, () -> policy.validateHost(null));
        assertThrows(BizException.class, () -> policy.validateHost("   "));
    }

    @Test
    void validateHostRejectsUppercase() {
        assertThrows(BizException.class, () -> policy.validateHost("Example.com"));
    }

    @Test
    void validateHostRejectsInvalidFormat() {
        assertThrows(BizException.class, () -> policy.validateHost("nodot"));
        assertThrows(BizException.class, () -> policy.validateHost("-leading.com"));
        assertThrows(BizException.class, () -> policy.validateHost("trailing-.com"));
    }

    @Test
    void validateHostAcceptsValidHosts() {
        policy.validateHost("example.com");
        policy.validateHost("a.b.example.com");
        policy.validateHost("my-app.example.com");
    }

    @Test
    void findBestMatchPrefersLongestSuffix() {
        List<String> candidates = List.of("example.com", "a.example.com");
        Optional<String> match = policy.findBestMatch("foo.a.example.com", candidates, identity);
        assertTrue(match.isPresent());
        assertEquals("a.example.com", match.get());
    }

    @Test
    void findBestMatchAllowsExactMatch() {
        List<String> candidates = List.of("example.com");
        assertEquals(Optional.of("example.com"),
                policy.findBestMatch("example.com", candidates, identity));
    }

    @Test
    void findBestMatchRequiresDotBoundary() {
        // "barexample.com" must NOT match candidate "example.com"
        List<String> candidates = List.of("example.com");
        assertTrue(policy.findBestMatch("barexample.com", candidates, identity).isEmpty());
    }

    @Test
    void findBestMatchIsCaseInsensitive() {
        List<String> candidates = List.of("example.com");
        assertEquals(Optional.of("example.com"),
                policy.findBestMatch("API.EXAMPLE.COM", candidates, identity));
    }

    @Test
    void findBestMatchReturnsEmptyForNullOrEmptyInputs() {
        assertTrue(policy.findBestMatch(null, List.of("example.com"), identity).isEmpty());
        assertTrue(policy.findBestMatch("example.com", List.of(), identity).isEmpty());
        assertTrue(policy.findBestMatch("example.com", null, identity).isEmpty());
        assertTrue(policy.findBestMatch("   ", List.of("example.com"), identity).isEmpty());
    }

    @Test
    void findBestMatchSkipsCandidatesWithNullHost() {
        List<String> candidates = java.util.Arrays.asList(null, "example.com");
        assertEquals(Optional.of("example.com"),
                policy.findBestMatch("a.example.com", candidates, identity));
    }
}
