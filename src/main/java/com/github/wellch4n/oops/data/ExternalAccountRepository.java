package com.github.wellch4n.oops.data;

import com.github.wellch4n.oops.enums.ExternalAccountProvider;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExternalAccountRepository extends JpaRepository<ExternalAccount, String> {
    Optional<ExternalAccount> findByProviderAndProviderUserId(ExternalAccountProvider provider, String providerUserId);
}
