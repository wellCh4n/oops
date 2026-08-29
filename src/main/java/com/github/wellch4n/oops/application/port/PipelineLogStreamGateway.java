package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.environment.Environment;

public interface PipelineLogStreamGateway {
    AutoCloseable stream(Pipeline pipeline, Environment environment, StreamSink sink);
}
