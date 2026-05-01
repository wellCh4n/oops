package com.github.wellch4n.oops.interfaces.dto;

import com.github.wellch4n.oops.domain.shared.UserRole;

public record UpdateUserRequest(UserRole role, String email, String password) {
}
