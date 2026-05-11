package com.github.wellch4n.oops.infrastructure.config;

import com.github.wellch4n.oops.application.port.PipelineLogStreamGateway;
import com.github.wellch4n.oops.application.port.PodLogStreamGateway;
import com.github.wellch4n.oops.application.port.TerminalSessionGateway;
import com.github.wellch4n.oops.application.service.EnvironmentService;
import com.github.wellch4n.oops.application.service.PipelineService;
import com.github.wellch4n.oops.application.service.SandboxInstanceService;
import com.github.wellch4n.oops.interfaces.websocket.PipelineLogWebSocketHandler;
import com.github.wellch4n.oops.interfaces.websocket.PodLogWebSocketHandler;
import com.github.wellch4n.oops.interfaces.websocket.SandboxTerminalWebSocketHandler;
import com.github.wellch4n.oops.interfaces.websocket.TerminalWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * @author wellCh4n
 * @date 2025/7/9
 */

@Configuration
@EnableWebSocket
public class WebSocketConfiguration implements WebSocketConfigurer {

    private final EnvironmentService environmentService;
    private final PipelineService pipelineService;
    private final SandboxInstanceService sandboxInstanceService;
    private final TerminalSessionGateway terminalSessionGateway;
    private final PodLogStreamGateway podLogStreamGateway;
    private final PipelineLogStreamGateway pipelineLogStreamGateway;

    public WebSocketConfiguration(
            EnvironmentService environmentService,
            PipelineService pipelineService,
            SandboxInstanceService sandboxInstanceService,
            TerminalSessionGateway terminalSessionGateway,
            PodLogStreamGateway podLogStreamGateway,
            PipelineLogStreamGateway pipelineLogStreamGateway
    ) {
        this.environmentService = environmentService;
        this.pipelineService = pipelineService;
        this.sandboxInstanceService = sandboxInstanceService;
        this.terminalSessionGateway = terminalSessionGateway;
        this.podLogStreamGateway = podLogStreamGateway;
        this.pipelineLogStreamGateway = pipelineLogStreamGateway;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry
                .addHandler(new TerminalWebSocketHandler(environmentService, terminalSessionGateway), "/api/namespaces/{namespace}/applications/{app}/pods/{pod}/terminal")
                .addHandler(new PodLogWebSocketHandler(environmentService, podLogStreamGateway), "/api/namespaces/{namespace}/applications/{app}/pods/{pod}/log")
                .addHandler(new PipelineLogWebSocketHandler(environmentService, pipelineService, pipelineLogStreamGateway), "/api/namespaces/{namespace}/applications/{app}/pipelines/{pipelineId}/log")
                .addHandler(new SandboxTerminalWebSocketHandler(sandboxInstanceService, terminalSessionGateway), "/api/sandbox/instances/{sandboxId}/terminal")
                .setAllowedOrigins("*");
    }
}
