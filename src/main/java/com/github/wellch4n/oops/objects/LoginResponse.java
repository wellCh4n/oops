package com.github.wellch4n.oops.objects;

import com.github.wellch4n.oops.enums.UserRole;

public record LoginResponse(String token, String username, UserRole role) {}
