package com.github.wellch4n.oops.application.event;

import com.github.wellch4n.oops.domain.shared.ExternalAccountProvider;
import java.time.LocalDateTime;

/**
 * An external identity provider reports that one of its users is gone — a Feishu resignation, for instance.
 *
 * <p>Deliberately says nothing about what OOPS should do about it. The provider adapter publishes the fact; a
 * listener decides the consequence.
 *
 * @param providerUserId the id in the provider's own namespace, matching {@code ExternalAccount.providerUserId}
 * @param email best-effort fallback for accounts linked before the id was recorded, may be {@code null}
 */
public record ExternalUserDeactivatedEvent(
        ExternalAccountProvider provider,
        String providerUserId,
        String email,
        String name,
        LocalDateTime occurredAt
) {
}
