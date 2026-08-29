package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import com.github.wellch4n.oops.domain.shared.ExternalAccountProvider;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ExternalAccountPersistenceAdapter implements com.github.wellch4n.oops.application.port.repository.ExternalAccountRepository {
    private final ExternalAccountRepository externalAccountRepository;

    public ExternalAccountPersistenceAdapter(ExternalAccountRepository externalAccountRepository) {
        this.externalAccountRepository = externalAccountRepository;
    }

    @Override
    public Optional<com.github.wellch4n.oops.domain.identity.ExternalAccount> findByProviderAndProviderUserId(
            ExternalAccountProvider provider,
            String providerUserId
    ) {
        return externalAccountRepository.findByProviderAndProviderUserId(provider, providerUserId)
                .map(PersistenceMapper::toDomain);
    }

    @Override
    public Optional<com.github.wellch4n.oops.domain.identity.ExternalAccount> findByProviderAndUserId(
            ExternalAccountProvider provider,
            String userId
    ) {
        return externalAccountRepository.findByProviderAndUserId(provider, userId)
                .map(PersistenceMapper::toDomain);
    }

    @Override
    public com.github.wellch4n.oops.domain.identity.ExternalAccount save(com.github.wellch4n.oops.domain.identity.ExternalAccount account) {
        return PersistenceMapper.toDomain(externalAccountRepository.save(PersistenceMapper.toEntity(account)));
    }
}
