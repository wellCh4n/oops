package com.github.wellch4n.oops.objects;

import com.github.wellch4n.oops.enums.UserRole;

public record UpdateUserRequest(UserRole role, String email, String password) {
}
