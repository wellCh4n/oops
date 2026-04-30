package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.infrastructure.persistence.jpa.Environment;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.Pipeline;

public interface PipelineLogStreamGateway {
    AutoCloseable stream(Pipeline pipeline, Environment environment, StreamSink sink);
}
