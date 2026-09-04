package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.environment.Environment;

/**
 * Live view of a pipeline's build job, split in two so a viewer only pays for what it looks at.
 *
 * <p>The build steps are init containers, which Kubernetes runs strictly one after another, so at
 * any moment at most one of them has a log worth following. The step watch is cheap and tells the
 * viewer which one that is; the log stream is opened for a single container, and the viewer opens
 * another only when it wants to look at another step.
 *
 * <p>Each returned handle stops the work and releases the cluster connections behind it. A stream
 * always ends with an {@code end} event so the receiver knows not to reconnect; an {@code error}
 * event before it says why there is nothing more to show.
 */
public interface PipelineLogStreamGateway {

    /**
     * Pushes a {@code steps} event naming the build containers in execution order, then a
     * {@code status} snapshot of every step whenever the build pod changes, until the pod finishes.
     */
    AutoCloseable watchSteps(Pipeline pipeline, Environment environment, EventStreamSink sink);

    /**
     * Streams one container's log as {@code log} batches until that container has terminated and its
     * output is drained. A container that has not started yet is waited for. Each batch carries the
     * time of its last stamped line as the event id; a client reconnecting with that id as
     * {@code lastEventId} gets only the lines written after it.
     */
    AutoCloseable streamContainerLog(
            Pipeline pipeline,
            Environment environment,
            String container,
            String lastEventId,
            EventStreamSink sink
    );
}
