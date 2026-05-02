package com.github.wellch4n.oops.application.dto;

import com.github.wellch4n.oops.domain.shared.UserRole;

public record UpdateUserCommand(UserRole role, String email, String password) {
}
