package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.application.dto.ResourceMetric;
import com.github.wellch4n.oops.domain.environment.Environment;
import java.time.Duration;
import java.util.List;

/**
 * Answers one question against a cluster's monitoring backend: which of an application's pods have stayed above a
 * usage threshold for an entire window.
 *
 * <p>The "for the entire window" part is deliberately the probe's job rather than the caller's. Pushing it into the
 * backend's query language means OOPS keeps no time series of its own and no rolling buffer — the same reason the
 * usage charts store nothing. A caller that sampled once a minute and counted consecutive hits would have to survive
 * restarts and missed ticks to mean the same thing.
 */
public interface ResourceAlertProbe {

    /**
     * @param threshold millicores for {@link ResourceMetric#CPU}, bytes for {@link ResourceMetric#MEMORY}
     * @param window    how long usage must have stayed above {@code threshold}
     * @return names of the offending pods, empty when none — never null
     */
    List<String> podsSustainedAbove(Environment environment,
                                    String namespace,
                                    String applicationName,
                                    ResourceMetric metric,
                                    long threshold,
                                    Duration window);
}
