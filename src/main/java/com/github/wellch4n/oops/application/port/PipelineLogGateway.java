package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.infrastructure.persistence.jpa.Environment;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.Pipeline;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface PipelineLogGateway {
    SseEmitter watch(Pipeline pipeline, Environment environment);
}
