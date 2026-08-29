package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.environment.Environment;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface PipelineLogGateway {
    SseEmitter watch(Pipeline pipeline, Environment environment);
}
