package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.application.dto.PodLogRetention;
import com.github.wellch4n.oops.domain.environment.Environment;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;

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

    /**
     * Writes the lines a pod logged inside a time window to the output, one stamped line per row as
     * the kubelet hands them over, without following.
     *
     * @throws IOException when the pod is gone or its log cannot be read
     */
    void download(Environment environment, String namespace, String podName, Instant since, Instant until, OutputStream output) throws IOException;

    /** The log retention of the node the pod runs on; fields are null when it cannot be read. */
    PodLogRetention retention(Environment environment, String namespace, String podName);
}
