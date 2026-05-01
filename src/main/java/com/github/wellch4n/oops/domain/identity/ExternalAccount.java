package com.github.wellch4n.oops.domain.identity;

import com.github.wellch4n.oops.domain.shared.BaseAggregateRoot;
import com.github.wellch4n.oops.domain.shared.ExternalAccountProvider;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ExternalAccount extends BaseAggregateRoot {
    private String email;
    private ExternalAccountProvider provider;
    private String providerUserId;
    private String userId;
}
