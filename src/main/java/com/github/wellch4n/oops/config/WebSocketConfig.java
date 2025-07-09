package com.github.wellch4n.oops.config;

import com.github.wellch4n.oops.controller.TerminalWebSocketHandler;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Bean;
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

    @Override
    public void registerWebSocketHandlers(@NotNull WebSocketHandlerRegistry registry) {
        registry.addHandler(
                new TerminalWebSocketHandler(),
                "/api/namespaces/{namespace}/applications/{app}/pods/{pod}/terminal"
        ).setAllowedOrigins("*");
    }
}
