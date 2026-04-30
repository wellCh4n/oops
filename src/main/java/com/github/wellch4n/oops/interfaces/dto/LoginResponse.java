package com.github.wellch4n.oops.interfaces.dto;

import com.github.wellch4n.oops.domain.shared.UserRole;

public record LoginResponse(String token, String id, String username, UserRole role) {}
