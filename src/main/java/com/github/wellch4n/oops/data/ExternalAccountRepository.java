package com.github.wellch4n.oops.data;

import com.github.wellch4n.oops.enums.ExternalAccountProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExternalAccountRepository extends JpaRepository<ExternalAccount, String> {
    Optional<ExternalAccount> findByProviderAndProviderUserId(ExternalAccountProvider provider, String providerUserId);
}