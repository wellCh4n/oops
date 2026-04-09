package com.github.wellch4n.oops.config;

import com.github.wellch4n.oops.controller.PipelineLogWebSocketHandler;
import com.github.wellch4n.oops.controller.PodLogWebSocketHandler;
//import com.github.wellch4n.oops.controller.ExplorerWebSocketHandler;
import com.github.wellch4n.oops.controller.TerminalWebSocketHandler;
import com.github.wellch4n.oops.service.EnvironmentService;
import com.github.wellch4n.oops.service.PipelineService;
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
    private final PipelineService pipelineService;

    public WebSocketConfig(EnvironmentService environmentService, PipelineService pipelineService) {
        this.environmentService = environmentService;
        this.pipelineService = pipelineService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry
                .addHandler(new TerminalWebSocketHandler(environmentService), "/api/namespaces/{namespace}/applications/{app}/pods/{pod}/terminal")
                .addHandler(new PodLogWebSocketHandler(environmentService), "/api/namespaces/{namespace}/applications/{app}/pods/{pod}/log")
                .addHandler(new PipelineLogWebSocketHandler(environmentService, pipelineService), "/api/namespaces/{namespace}/applications/{app}/pipelines/{pipelineId}/log")
//                .addHandler(new ExplorerWebSocketHandler(), "/api/namespaces/{namespace}/applications/{app}/pods/{pod}/explorer")
                .setAllowedOrigins("*");
    }
}
