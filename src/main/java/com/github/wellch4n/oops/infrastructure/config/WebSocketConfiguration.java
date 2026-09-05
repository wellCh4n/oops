package com.github.wellch4n.oops.infrastructure.config;

import com.github.wellch4n.oops.application.port.TerminalSessionGateway;
import com.github.wellch4n.oops.application.service.EnvironmentService;
import com.github.wellch4n.oops.application.service.SandboxInstanceService;
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
    private final SandboxInstanceService sandboxInstanceService;
    private final TerminalSessionGateway terminalSessionGateway;

    public WebSocketConfiguration(
            EnvironmentService environmentService,
            SandboxInstanceService sandboxInstanceService,
            TerminalSessionGateway terminalSessionGateway
    ) {
        this.environmentService = environmentService;
        this.sandboxInstanceService = sandboxInstanceService;
        this.terminalSessionGateway = terminalSessionGateway;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry
                .addHandler(new TerminalWebSocketHandler(environmentService, terminalSessionGateway), "/api/namespaces/{namespace}/applications/{app}/pods/{pod}/terminal")
                .addHandler(new SandboxTerminalWebSocketHandler(sandboxInstanceService, terminalSessionGateway),
                        "/api/sandbox/instances/{sandboxId}/terminal",
                        "/openapi/sandbox/instances/{sandboxId}/terminal")
                .setAllowedOrigins("*");
    }
}
