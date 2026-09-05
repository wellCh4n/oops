package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.environment.Environment;

/**
 * Follows one application pod's log as an event stream: {@code log} batches of {@code {time, text}}
 * lines (id = the last stamped time in the batch), {@code error} when there is nothing to show,
 * and {@code end} once the container's output is over.
 */
public interface PodLogStreamGateway {

    /**
     * @param lastEventId the id of the last {@code log} event the receiver saw, or null to start
     *                    from the tail of the log; the stream resumes after that line
     * @return a handle that stops the stream and releases what it holds
     */
    AutoCloseable stream(Environment environment, String namespace, String podName, String lastEventId, EventStreamSink sink);
}
