package com.github.wellch4n.oops.infrastructure.kubernetes;

import com.github.wellch4n.oops.shared.exception.EnvironmentUnreachableException;
import io.fabric8.kubernetes.client.KubernetesClientException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
public class KubernetesExceptionTranslationAspect {

    @Around("execution(* com.github.wellch4n.oops.infrastructure.kubernetes..*.*(..))")
    public Object translate(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            return joinPoint.proceed();
        } catch (KubernetesClientException e) {
            throw new EnvironmentUnreachableException(buildMessage(e), e);
        }
    }

    private String buildMessage(KubernetesClientException e) {
        int code = e.getCode();
        log.warn("Kubernetes client error (code={}): {}", code, e.getMessage());

        if (code <= 0) {
            return "Failed to reach Kubernetes API server: " + rootCauseMessage(e);
        }
        if (code == 401 || code == 403) {
            return "Kubernetes API authentication failed (HTTP " + code
                    + "). Please verify the environment's API token.";
        }
        return "Kubernetes API error (HTTP " + code + "): " + e.getMessage();
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message != null ? message : cursor.getClass().getSimpleName();
    }
}
