package com.github.wellch4n.oops.shared.util;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilsTests {

    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "secret", "test-secret-key-at-least-32-bytes-long!");
        ReflectionTestUtils.setField(jwtUtils, "expiration", 3600000L);
    }

    @Test
    void generateToken_producesNonBlankToken() {
        String token = jwtUtils.generateToken("user-1", "alice", "ADMIN");

        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    void parseToken_returnsCorrectClaims() {
        String token = jwtUtils.generateToken("user-1", "alice", "ADMIN");

        Claims claims = jwtUtils.parseToken(token);

        assertEquals("alice", claims.getSubject());
        assertEquals("user-1", claims.get("userId", String.class));
        assertEquals("ADMIN", claims.get("role", String.class));
    }

    @Test
    void getUsername_returnsSubject() {
        String token = jwtUtils.generateToken("user-1", "bob", "USER");

        assertEquals("bob", jwtUtils.getUsername(token));
    }

    @Test
    void getUserId_returnsCustomClaim() {
        String token = jwtUtils.generateToken("user-42", "charlie", "USER");

        assertEquals("user-42", jwtUtils.getUserId(token));
    }

    @Test
    void getRole_returnsRoleClaim() {
        String token = jwtUtils.generateToken("user-1", "alice", "ADMIN");

        assertEquals("ADMIN", jwtUtils.getRole(token));
    }

    @Test
    void isValid_returnsTrue_forFreshToken() {
        String token = jwtUtils.generateToken("user-1", "alice", "USER");

        assertTrue(jwtUtils.isValid(token));
    }

    @Test
    void isValid_returnsFalse_forExpiredToken() {
        ReflectionTestUtils.setField(jwtUtils, "expiration", -1000L);
        String token = jwtUtils.generateToken("user-1", "alice", "USER");

        assertFalse(jwtUtils.isValid(token));
    }

    @Test
    void isValid_returnsFalse_forMalformedToken() {
        assertFalse(jwtUtils.isValid("not.a.valid.token"));
    }

    @Test
    void isValid_returnsFalse_forNullToken() {
        assertFalse(jwtUtils.isValid(null));
    }
}
