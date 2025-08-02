package com.github.wellch4n.oops.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.annotation.WithoutKubernetes;
import com.github.wellch4n.oops.objects.Result;
import io.kubernetes.client.openapi.ApiClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */

@Component
public class KubernetesApiClientInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(@NotNull HttpServletRequest request,
                             @NotNull HttpServletResponse response,
                             @NotNull Object handler) throws Exception {

        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        WithoutKubernetes withoutKubernetes = handlerMethod.getMethodAnnotation(WithoutKubernetes.class);

        if (withoutKubernetes != null) {
            return true;
        }

        String environment = request.getHeader("OOPS-Environment");

        EnvironmentContext.setEnvironment(environment);
        ApiClient apiClient = KubernetesClientFactory.getClient();

        if (apiClient == null) {
            response.setContentType("application/json");
            Result<Object> failure = Result.failure("Kubernetes API server URL or token is not configured in the system.");
            response.getWriter().write(objectMapper.writeValueAsString(failure));
            return false;
        }

        return true;
    }
}
