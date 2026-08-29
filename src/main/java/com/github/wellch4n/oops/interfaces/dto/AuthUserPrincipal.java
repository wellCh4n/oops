package com.github.wellch4n.oops.interfaces.dto;

import java.security.Principal;

public record AuthUserPrincipal(String userId, String username) implements Principal {

    @Override
    public String getName() {
        return username;
    }
}
