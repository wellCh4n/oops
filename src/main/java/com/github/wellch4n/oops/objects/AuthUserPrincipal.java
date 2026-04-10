package com.github.wellch4n.oops.objects;

import java.security.Principal;

public record AuthUserPrincipal(String userId, String username) implements Principal {

    @Override
    public String getName() {
        return username;
    }
}
