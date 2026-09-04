package com.github.wellch4n.oops.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.github.wellch4n.oops.application.port.ResourceAlertProbe;
import com.github.wellch4n.oops.application.service.EnvironmentService;
import com.github.wellch4n.oops.infrastructure.config.ResourceAlertProperties;
import com.github.wellch4n.oops.infrastructure.lock.NamedLockRegistry;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationAlertStateRepository;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationRuntimeSpecRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Alerting is opt-in, and the mechanism is the bean condition rather than a flag read on each tick. These assertions
 * are what stop an upgrade from quietly starting to message people: an installation whose config never mentions
 * {@code oops.metrics.alert} must end up with no scheduled scan at all.
 */
class ResourceAlertOptInTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ResourceAlertProperties.class, ResourceAlertScanJob.class)
            .withBean(ApplicationRuntimeSpecRepository.class, () -> mock(ApplicationRuntimeSpecRepository.class))
            .withBean(ApplicationAlertStateRepository.class, () -> mock(ApplicationAlertStateRepository.class))
            .withBean(EnvironmentService.class, () -> mock(EnvironmentService.class))
            .withBean(ResourceAlertProbe.class, () -> mock(ResourceAlertProbe.class))
            .withBean(NamedLockRegistry.class, () -> mock(NamedLockRegistry.class));

    @Test
    void scanJobIsAbsentWhenNothingIsConfigured() {
        runner.run(context -> assertNoAlerting(context));
    }

    @Test
    void scanJobIsAbsentWhenExplicitlyDisabled() {
        runner.withPropertyValues("oops.metrics.alert.enabled=false")
                .run(context -> assertNoAlerting(context));
    }

    @Test
    void scanJobIsLoadedOnceEnabled() {
        runner.withPropertyValues("oops.metrics.alert.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ResourceAlertScanJob.class);
                    assertThat(context).hasSingleBean(ResourceAlertProperties.class);
                });
    }

    /** The properties bean goes too, so a stray injection cannot resurrect alerting on its own. */
    private void assertNoAlerting(AssertableApplicationContext context) {
        assertThat(context).doesNotHaveBean(ResourceAlertScanJob.class);
        assertThat(context).doesNotHaveBean(ResourceAlertProperties.class);
    }
}
