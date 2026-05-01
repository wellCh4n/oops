package com.github.wellch4n.oops.domain.identity;

import com.github.wellch4n.oops.domain.shared.BaseAggregateRoot;
import com.github.wellch4n.oops.domain.shared.UserRole;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class User extends BaseAggregateRoot {
    private String username;
    private String email;
    private String password;
    private UserRole role;
}
