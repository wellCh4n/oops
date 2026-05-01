package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.environment.Environment;

public interface PodLogStreamGateway {
    AutoCloseable stream(Environment environment, String namespace, String podName, StreamSink sink);
}
