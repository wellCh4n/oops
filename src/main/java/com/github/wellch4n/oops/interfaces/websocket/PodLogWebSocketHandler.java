package com.github.wellch4n.oops.interfaces.websocket;

import com.github.wellch4n.oops.application.port.PodLogStreamGateway;
import com.github.wellch4n.oops.application.service.EnvironmentService;
import com.github.wellch4n.oops.domain.environment.Environment;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
public class PodLogWebSocketHandler extends AbstractWebSocketHandler {

    private final EnvironmentService environmentService;
    private final PodLogStreamGateway podLogStreamGateway;

    public PodLogWebSocketHandler(EnvironmentService environmentService, PodLogStreamGateway podLogStreamGateway) {
        this.environmentService = environmentService;
        this.podLogStreamGateway = podLogStreamGateway;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        URI uri = session.getUri();
        if (uri == null) return;

        String namespace = WebSocketSessionSupport.pathSegment(session, 3, "namespace");
        String pod = WebSocketSessionSupport.pathSegment(session, 7, "pod");
        if (namespace == null || pod == null) {
            return;
        }

        Map<String, String> params = UriComponentsBuilder.fromUri(uri)
                .build().getQueryParams().toSingleValueMap();
        String environmentName = params.get("env");
        Environment environment = environmentService.getEnvironment(environmentName);
        if (environment == null) {
            WebSocketSessionSupport.close(session, "Environment not found");
            return;
        }

        WebSocketStreamSink sink = new WebSocketStreamSink(session);
        AutoCloseable stream = podLogStreamGateway.stream(environment, namespace, pod, sink);
        session.getAttributes().put("streamSink", sink);
        session.getAttributes().put("podLogStream", stream);
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
        AutoCloseable stream = (AutoCloseable) session.getAttributes().get("podLogStream");
        WebSocketSessionSupport.closeQuietly(stream, log, "pod log stream");
        WebSocketSessionSupport.stopHeartbeat(session);
    }
}
