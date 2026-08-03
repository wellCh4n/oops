package com.github.wellch4n.oops.application.event;

import com.github.wellch4n.oops.application.port.repository.ExternalAccountRepository;
import com.github.wellch4n.oops.application.service.UserService;
import com.github.wellch4n.oops.domain.identity.ExternalAccount;
import com.github.wellch4n.oops.domain.identity.User;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Disables the OOPS account of someone who left the organisation.
 *
 * <p>Disabling rather than deleting: {@code enabled=false} is already enforced at login, in {@code JwtAuthFilter} and
 * in {@code OpenApiAuthFilter}, so an issued JWT and an OpenAPI access token both stop working on the next request,
 * while the applications the person owned keep a resolvable owner and an admin can reverse a mistake.
 *
 * <p>Runs synchronously, unlike the notification listeners: it is a single-row update on the SDK's own connection
 * thread, which has nothing else to do, so an {@code @Async} hop would buy nothing. Note that the SDK catches
 * whatever this throws and acknowledges the event anyway — a failure here is lost rather than redelivered, which is
 * why the catch below logs the person it was about instead of letting the SDK log a bare stack trace.
 */
@Slf4j
@Component
public class ExternalUserDeactivatedListener {

    private final ExternalAccountRepository externalAccountRepository;
    private final UserService userService;

    public ExternalUserDeactivatedListener(ExternalAccountRepository externalAccountRepository,
                                           UserService userService) {
        this.externalAccountRepository = externalAccountRepository;
        this.userService = userService;
    }

    @EventListener
    public void onExternalUserDeactivated(ExternalUserDeactivatedEvent event) {
        Optional<String> userId = resolveUserId(event);
        if (userId.isEmpty()) {
            // Not an error: plenty of people in the directory never signed in to OOPS.
            log.info("{} user {} left the organisation but has no linked OOPS account",
                    event.provider(), event.providerUserId());
            return;
        }

        try {
            if (userService.deactivateUser(userId.get())) {
                log.info("Disabled OOPS user {} after {} reported the account as removed",
                        userId.get(), event.provider());
            } else {
                // Says nothing about which of the two reasons it was — the last-admin guard logs its own warning,
                // so anything reaching here silently is simply an account that was already disabled.
                log.info("OOPS user {} was left as is after {} reported the account as removed",
                        userId.get(), event.provider());
            }
        } catch (Exception exception) {
            // Nothing will retry this, so the log is the only trace: name the account an admin has to disable by hand.
            log.error("Failed to disable OOPS user {} after {} reported {} as removed",
                    userId.get(), event.provider(), event.providerUserId(), exception);
        }
    }

    /**
     * Prefers the linked account, falling back to the address Feishu reports — accounts linked by an older login flow
     * may not carry a provider id, and an email match is still the same person.
     */
    private Optional<String> resolveUserId(ExternalUserDeactivatedEvent event) {
        Optional<ExternalAccount> account = externalAccountRepository
                .findByProviderAndProviderUserId(event.provider(), event.providerUserId());
        if (account.isPresent()) {
            return Optional.ofNullable(account.get().getUserId());
        }
        if (event.email() == null || event.email().isBlank()) {
            return Optional.empty();
        }
        return userService.findByEmail(event.email()).map(User::getId);
    }
}
