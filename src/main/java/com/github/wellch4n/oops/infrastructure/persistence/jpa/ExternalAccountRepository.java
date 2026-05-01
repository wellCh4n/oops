package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import com.github.wellch4n.oops.domain.shared.ExternalAccountProvider;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExternalAccountRepository extends JpaRepository<ExternalAccount, String> {
    Optional<ExternalAccount> findByProviderAndProviderUserId(ExternalAccountProvider provider, String providerUserId);

    Optional<ExternalAccount> findByProviderAndUserId(ExternalAccountProvider provider, String userId);
}
