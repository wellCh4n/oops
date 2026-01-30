package com.github.wellch4n.oops.config;

//import com.github.wellch4n.oops.controller.ExplorerWebSocketHandler;
import com.github.wellch4n.oops.controller.TerminalWebSocketHandler;
import com.github.wellch4n.oops.service.EnvironmentService;
import org.jetbrains.annotations.NotNull;
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
public class WebSocketConfig implements WebSocketConfigurer {

    private final EnvironmentService environmentService;

    public WebSocketConfig(EnvironmentService environmentService) {
        this.environmentService = environmentService;
    }

    @Override
    public void registerWebSocketHandlers(@NotNull WebSocketHandlerRegistry registry) {
        registry
                .addHandler(new TerminalWebSocketHandler(environmentService), "/api/namespaces/{namespace}/applications/{app}/pods/{pod}/terminal")
//                .addHandler(new ExplorerWebSocketHandler(), "/api/namespaces/{namespace}/applications/{app}/pods/{pod}/explorer")
                .setAllowedOrigins("*");
    }
}
