package com.github.wellch4n.oops.infrastructure.external.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.wellch4n.oops.infrastructure.config.FeishuProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Syncing resignations is opt-in, and the mechanism is the bean condition rather than a flag read per event. These
 * assertions are what stop an upgrade from quietly starting to take people's access away: an installation that never
 * asked for it must end up with no client at all, and therefore no connection.
 */
class FeishuEventOptInTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FeishuProperties.class, FeishuEventClient.class)
            .withPropertyValues("oops.feishu.app-id=cli_test", "oops.feishu.app-secret=secret");

    @Test
    void clientIsAbsentWhenNothingIsConfigured() {
        runner.run(context -> assertThat(context).doesNotHaveBean(FeishuEventClient.class));
    }

    /** Feishu login on its own must not drag resignation syncing in with it. */
    @Test
    void clientIsAbsentWhenOnlyFeishuIsEnabled() {
        runner.withPropertyValues("oops.feishu.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(FeishuEventClient.class));
    }

    /** Nor the other way round: without Feishu configured there are no credentials to dial out with. */
    @Test
    void clientIsAbsentWhenFeishuItselfIsDisabled() {
        runner.withPropertyValues("oops.feishu.sync-user-deactivation=true")
                .run(context -> assertThat(context).doesNotHaveBean(FeishuEventClient.class));
    }

    @Test
    void clientIsLoadedOnceBothAreOn() {
        runner.withPropertyValues("oops.feishu.enabled=true", "oops.feishu.sync-user-deactivation=true")
                .run(context -> assertThat(context).hasSingleBean(FeishuEventClient.class));
    }
}
