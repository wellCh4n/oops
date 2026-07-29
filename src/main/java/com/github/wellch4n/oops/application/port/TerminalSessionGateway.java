package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.environment.Environment;
import java.io.IOException;

public interface TerminalSessionGateway {

    /**
     * Opens a terminal onto a container.
     *
     * @param sessionKey identifies the shell across reconnects. Presenting the same key again
     *                   reattaches to the shell a dropped connection left behind, preserving its
     *                   working directory and any running command. A null key, or a container that
     *                   cannot host a resumable shell, yields a fresh shell that dies with the stream.
     */
    TerminalSession open(Environment environment, String namespace, String podName, String container, String sessionKey, StreamSink sink);

    /**
     * Ends the shell behind {@code sessionKey} for good. Called when the user closes the terminal
     * rather than losing it: without this, every closed tab would leave a shell running inside the
     * container until it restarts.
     */
    void terminate(Environment environment, String namespace, String podName, String container, String sessionKey);

    interface TerminalSession extends AutoCloseable {
        void write(byte[] bytes) throws IOException;

        @Override
        void close();
    }
}
