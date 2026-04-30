package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.infrastructure.persistence.jpa.Environment;

public interface PodLogStreamGateway {
    AutoCloseable stream(Environment environment, String namespace, String podName, StreamSink sink);
}
