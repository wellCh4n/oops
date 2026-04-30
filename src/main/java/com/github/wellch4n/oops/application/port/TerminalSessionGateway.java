package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.infrastructure.persistence.jpa.Environment;
import java.io.IOException;

public interface TerminalSessionGateway {
    TerminalSession open(Environment environment, String namespace, String podName, String container, StreamSink sink);

    interface TerminalSession extends AutoCloseable {
        void write(byte[] bytes) throws IOException;

        @Override
        void close();
    }
}
