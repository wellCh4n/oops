package com.github.wellch4n.oops.interfaces.websocket;

import com.github.wellch4n.oops.application.port.PipelineLogStreamGateway;
import com.github.wellch4n.oops.application.service.EnvironmentService;
import com.github.wellch4n.oops.application.service.PipelineService;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.Environment;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.Pipeline;
import java.io.IOException;
import java.net.URI;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

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
        URI uri = session.getUri();
        if (uri == null) {
            return;
        }

        String path = uri.getPath();
        String[] parts = path.split("/");
        String namespace = parts[3];
        String name = parts[5];
        String pipelineId = parts[7];

        Pipeline pipeline = pipelineService.getPipeline(namespace, name, pipelineId);
        Environment environment = environmentService.getEnvironment(pipeline.getEnvironment());

        WebSocketStreamSink sink = new WebSocketStreamSink(session);
        AutoCloseable stream = pipelineLogStreamGateway.stream(pipeline, environment, sink);
        session.getAttributes().put("streamSink", sink);
        session.getAttributes().put("pipelineLogStream", stream);

        Thread heartbeatThread = Thread.ofVirtual().start(() -> {
            while (session.isOpen()) {
                try {
                    Thread.sleep(10000);
                    sink.sendText("ping");
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException _) {
                    break;
                }
            }
        });
        session.getAttributes().put("heartbeatThread", heartbeatThread);
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
        if (stream != null) {
            try {
                stream.close();
            } catch (Exception _) {
            }
        }

        Thread heartbeatThread = (Thread) session.getAttributes().get("heartbeatThread");
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
        }
    }
}
