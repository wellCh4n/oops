package com.github.wellch4n.oops.domain.shared;

/**
 * Minimal view of the user performing an operation, carrying only what
 * access policies need — never credentials or tokens.
 */
public record Operator(String userId, UserRole role, boolean enabled) {

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }
}
