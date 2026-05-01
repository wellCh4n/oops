package com.github.wellch4n.oops.application.port.repository;

import com.github.wellch4n.oops.domain.identity.ExternalAccount;
import com.github.wellch4n.oops.domain.shared.ExternalAccountProvider;
import java.util.Optional;

public interface ExternalAccountRepository {
    Optional<ExternalAccount> findByProviderAndProviderUserId(ExternalAccountProvider provider, String providerUserId);

    Optional<ExternalAccount> findByProviderAndUserId(ExternalAccountProvider provider, String userId);

    ExternalAccount save(ExternalAccount account);
}
