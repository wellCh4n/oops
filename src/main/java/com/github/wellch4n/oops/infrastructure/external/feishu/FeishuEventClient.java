package com.github.wellch4n.oops.infrastructure.external.feishu;

import com.github.wellch4n.oops.application.event.ExternalUserDeactivatedEvent;
import com.github.wellch4n.oops.domain.shared.ExternalAccountProvider;
import com.github.wellch4n.oops.infrastructure.config.FeishuProperties;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.contact.ContactService;
import com.lark.oapi.service.contact.v3.model.P2UserDeletedV3;
import com.lark.oapi.service.contact.v3.model.UserEvent;
import com.lark.oapi.ws.Client;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Receives Feishu event subscriptions over the SDK's long connection.
 *
 * <p>Long connection rather than a webhook because OOPS is self-hosted and often has no address Feishu's servers can
 * reach; this way the backend dials out instead. It also removes the whole webhook attack surface — no public
 * endpoint, no verification token, no encrypt key, since the connection authenticates with the app credentials
 * themselves. The trade-off is that events only arrive while the process is up.
 *
 * <p>Deliberately does not touch OOPS state. It turns an event into a provider-neutral Spring event, so that what a
 * resignation means stays in {@code application/event} and Feishu stays in this package.
 *
 * <p>Gated on {@code oops.feishu.sync-user-deactivation} as well as {@code enabled}, and gated at the bean rather
 * than at the handler: resignations are the only thing subscribed, so with the switch off there is nothing to listen
 * for and no connection worth opening.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "oops.feishu", name = {"enabled", "sync-user-deactivation"}, havingValue = "true")
public class FeishuEventClient {

    private final ApplicationEventPublisher eventPublisher;
    private final EventDispatcher eventDispatcher;
    private final Client client;

    public FeishuEventClient(FeishuProperties feishuConfig, ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
        // Empty token and key: those belong to the webhook mode, where the request arrives unauthenticated and has to
        // prove itself. A long connection is already authenticated by the app credentials below.
        this.eventDispatcher = EventDispatcher.newBuilder("", "")
                .onP2UserDeletedV3(new ContactService.P2UserDeletedV3Handler() {
                    @Override
                    public void handle(P2UserDeletedV3 event) {
                        onUserDeleted(event);
                    }
                })
                .build();
        this.client = new Client.Builder(feishuConfig.getAppId(), feishuConfig.getAppSecret())
                .eventHandler(eventDispatcher)
                .build();
    }

    /**
     * Dials out once the rest of the application is up, on a virtual thread so a slow or unreachable Feishu cannot
     * hold up startup. Reconnection is the SDK's job — it retries on its own by default, and the only failure it
     * hands back is the kind no retry fixes.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void connect() {
        Thread.ofVirtual().name("feishu-event-client").start(() -> {
            try {
                client.start();
                log.info("Feishu event long connection established, syncing user deactivation");
            } catch (Throwable throwable) {
                log.error("Feishu event long connection failed to start, resignation events will not arrive: {}",
                        throwable.getMessage(), throwable);
            }
        });
    }

    /**
     * Feishu says an employee left the organisation. Publish the fact and let a listener decide the consequence.
     *
     * <p>No de-duplication: delivery is at least once, and disabling an account that is already disabled is a no-op.
     *
     * <p>Every event received is logged. Resignations are rare enough that the volume is irrelevant, and without
     * this line an event that matches no OOPS account, or one that arrives while the person is already disabled,
     * would leave no trace of having arrived at all.
     */
    private void onUserDeleted(P2UserDeletedV3 event) {
        UserEvent deletedUser = event.getEvent() == null ? null : event.getEvent().getObject();
        if (deletedUser == null || isBlank(deletedUser.getUserId())) {
            log.warn("Feishu user deletion event carries no user id, ignoring");
            return;
        }
        log.info("Feishu reported user {} ({}) as removed from the organisation",
                deletedUser.getUserId(), deletedUser.getName());

        eventPublisher.publishEvent(new ExternalUserDeactivatedEvent(
                ExternalAccountProvider.FEISHU,
                deletedUser.getUserId(),
                resolveEmail(deletedUser),
                deletedUser.getName(),
                LocalDateTime.now()));
    }

    /** Mirrors {@code FeishuAuthStrategy}, so the fallback lookup by email matches the address accounts were linked with. */
    private String resolveEmail(UserEvent deletedUser) {
        if (!isBlank(deletedUser.getEnterpriseEmail())) {
            return deletedUser.getEnterpriseEmail();
        }
        return isBlank(deletedUser.getEmail()) ? null : deletedUser.getEmail();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Lets a test drive a raw event payload through the same dispatcher the connection feeds. */
    EventDispatcher eventDispatcher() {
        return eventDispatcher;
    }
}
