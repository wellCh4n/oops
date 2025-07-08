package com.github.wellch4n.oops.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.annotation.WithoutKubernetes;
import com.github.wellch4n.oops.data.SystemConfig;
import com.github.wellch4n.oops.data.SystemConfigRepository;
import com.github.wellch4n.oops.enums.SystemConfigKeys;
import com.github.wellch4n.oops.objects.Result;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
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

    private final SystemConfigRepository systemConfigRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KubernetesApiClientInterceptor(SystemConfigRepository systemConfigRepository) {
        this.systemConfigRepository = systemConfigRepository;
    }

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

        SystemConfig apiServer = systemConfigRepository.findByConfigKey(SystemConfigKeys.KUBERNETES_API_SERVER_URL);
        SystemConfig token = systemConfigRepository.findByConfigKey(SystemConfigKeys.KUBERNETES_API_SERVER_TOKEN);

        if (apiServer == null || token == null || StringUtils.isAnyEmpty(apiServer.getConfigValue(), token.getConfigValue())) {
            response.setContentType("application/json");
            Result<Object> failure = Result.failure("Kubernetes API server URL or token is not configured in the system.");
            response.getWriter().write(objectMapper.writeValueAsString(failure));
            return false;
        }

        ApiClient apiClient = Config.fromToken(apiServer.getConfigValue(), token.getConfigValue(), false);
        KubernetesContext.setApiClient(apiClient);
        return true;
    }
}
