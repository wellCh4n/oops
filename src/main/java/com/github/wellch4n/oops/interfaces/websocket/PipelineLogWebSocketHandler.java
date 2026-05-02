package com.github.wellch4n.oops.interfaces.websocket;

import com.github.wellch4n.oops.application.port.PipelineLogStreamGateway;
import com.github.wellch4n.oops.application.service.EnvironmentService;
import com.github.wellch4n.oops.application.service.PipelineService;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.environment.Environment;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

@Slf4j
public class PipelineLogWebSocketHandler extends AbstractWebSocketHandler {

    private final EnvironmentService environmentService;
    private final PipelineService pipelineService;
    private final PipelineLogStreamGateway pipelineLogStreamGateway;

    public PipelineLogWebSocketHandler(
            EnvironmentService environmentService,
            PipelineService pipelineService,
            PipelineLogStreamGateway pipelineLogStreamGateway
    ) {
        this.environmentService = environmentService;
        this.pipelineService = pipelineService;
        this.pipelineLogStreamGateway = pipelineLogStreamGateway;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String namespace = WebSocketSessionSupport.pathSegment(session, 3, "namespace");
        String name = WebSocketSessionSupport.pathSegment(session, 5, "application");
        String pipelineId = WebSocketSessionSupport.pathSegment(session, 7, "pipeline");
        if (namespace == null || name == null || pipelineId == null) {
            return;
        }

        Pipeline pipeline = pipelineService.getPipeline(namespace, name, pipelineId);
        if (pipeline == null) {
            WebSocketSessionSupport.close(session, "Pipeline not found");
            return;
        }
        Environment environment = environmentService.getEnvironment(pipeline.getEnvironment());
        if (environment == null) {
            WebSocketSessionSupport.close(session, "Environment not found");
            return;
        }

        WebSocketStreamSink sink = new WebSocketStreamSink(session);
        AutoCloseable stream = pipelineLogStreamGateway.stream(pipeline, environment, sink);
        session.getAttributes().put("streamSink", sink);
        session.getAttributes().put("pipelineLogStream", stream);
        WebSocketSessionSupport.startHeartbeat(session, sink, log);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        if ("ping".equals(message.getPayload())) {
            WebSocketStreamSink sink = (WebSocketStreamSink) session.getAttributes().get("streamSink");
            if (sink != null) {
                sink.sendText("pong");
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        AutoCloseable stream = (AutoCloseable) session.getAttributes().get("pipelineLogStream");
        WebSocketSessionSupport.closeQuietly(stream, log, "pipeline log stream");
        WebSocketSessionSupport.stopHeartbeat(session);
    }
}
