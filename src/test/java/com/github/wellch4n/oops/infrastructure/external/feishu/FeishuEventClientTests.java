package com.github.wellch4n.oops.infrastructure.external.feishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.github.wellch4n.oops.application.event.ExternalUserDeactivatedEvent;
import com.github.wellch4n.oops.domain.shared.ExternalAccountProvider;
import com.github.wellch4n.oops.infrastructure.config.FeishuProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The client's job is to turn a Feishu event into a provider-neutral one and nothing more.
 *
 * <p>Payloads are pushed straight through the dispatcher the long connection feeds, so these run without a socket.
 */
class FeishuEventClientTests {

    private List<Object> published;
    private FeishuEventClient client;

    @BeforeEach
    void setUp() {
        published = new ArrayList<>();
        FeishuProperties properties = new FeishuProperties();
        properties.setEnabled(true);
        properties.setAppId("cli_test");
        properties.setAppSecret("secret");
        ApplicationEventPublisher publisher = published::add;
        client = new FeishuEventClient(properties, publisher);
    }

    @Test
    void publishesADeactivationWhenSomeoneLeaves() throws Throwable {
        deliver("""
                {"schema":"2.0",
                 "header":{"event_id":"evt-1","event_type":"contact.user.deleted_v3","create_time":"1608725989000",
                           "app_id":"cli_test","tenant_key":"tenant"},
                 "event":{"object":{"open_id":"ou_x","user_id":"e33ggbyz","name":"张三",
                                    "email":"zhangsan@example.com"}}}
                """);

        assertThat(published).hasSize(1);
        ExternalUserDeactivatedEvent event = (ExternalUserDeactivatedEvent) published.getFirst();
        assertThat(event.provider()).isEqualTo(ExternalAccountProvider.FEISHU);
        assertThat(event.providerUserId()).isEqualTo("e33ggbyz");
        assertThat(event.email()).isEqualTo("zhangsan@example.com");
    }

    /** The enterprise address is what {@code FeishuAuthStrategy} links accounts with, so it has to win here too. */
    @Test
    void prefersTheEnterpriseEmail() throws Throwable {
        deliver("""
                {"schema":"2.0",
                 "header":{"event_id":"evt-4","event_type":"contact.user.deleted_v3","create_time":"1608725989000"},
                 "event":{"object":{"user_id":"e33ggbyz","name":"张三","email":"personal@example.com",
                                    "enterprise_email":"zhangsan@corp.example.com"}}}
                """);

        ExternalUserDeactivatedEvent event = (ExternalUserDeactivatedEvent) published.getFirst();
        assertThat(event.email()).isEqualTo("zhangsan@corp.example.com");
    }

    /** A payload with nothing to act on must be swallowed, not thrown back at the connection. */
    @Test
    void toleratesAnEventWithoutAUserId() {
        assertThatCode(() -> deliver("""
                {"schema":"2.0",
                 "header":{"event_id":"evt-3","event_type":"contact.user.deleted_v3","create_time":"1608725989000"},
                 "event":{"object":{"open_id":"ou_x"}}}
                """)).doesNotThrowAnyException();

        assertThat(published).isEmpty();
    }

    private void deliver(String body) throws Throwable {
        client.eventDispatcher().doWithoutValidation(body.getBytes(StandardCharsets.UTF_8));
    }
}
