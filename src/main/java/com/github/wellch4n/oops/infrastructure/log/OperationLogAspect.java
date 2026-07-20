package com.github.wellch4n.oops.infrastructure.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.application.service.OperationLogService;
import com.github.wellch4n.oops.interfaces.dto.AuthUserPrincipal;
import com.github.wellch4n.oops.shared.log.Loggable;
import com.github.wellch4n.oops.shared.log.OperationSource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;

/**
 * AOP aspect for automatic operation logging.
 * Intercepts methods annotated with @Loggable and records operation logs.
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class OperationLogAspect {

    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;

    @Around("@annotation(loggable)")
    public Object recordOperation(ProceedingJoinPoint joinPoint, Loggable loggable) throws Throwable {
        HttpServletRequest request = getCurrentRequest();
        OperationSource source = determineSource(request);
        AuthUserPrincipal principal = getCurrentUser();

        String namespace = extractNamespace(joinPoint);
        String resourceId = null;
        String environmentName = extractEnvironmentName(request);
        boolean success = true;
        String errorMessage = null;
        Object result = null;

        try {
            result = joinPoint.proceed();
            resourceId = extractResourceId(result, joinPoint);
            return result;
        } catch (Exception exception) {
            success = false;
            errorMessage = exception.getMessage();
            throw exception;
        } finally {
            // Record operation log asynchronously
            String details = loggable.includeDetails() ? buildDetails(joinPoint, result) : null;

            operationLogService.log(
                principal != null ? principal.userId() : null,
                principal != null ? principal.username() : null,
                source,
                loggable.operation(),
                loggable.resourceType(),
                resourceId,
                namespace,
                environmentName,
                request != null ? request.getRemoteAddr() : null,
                details,
                success,
                errorMessage
            );
        }
    }

    private HttpServletRequest getCurrentRequest() {
        try {
            ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attributes != null ? attributes.getRequest() : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private OperationSource determineSource(HttpServletRequest request) {
        if (request == null) {
            return OperationSource.SYSTEM;
        }

        String uri = request.getRequestURI();
        if (uri.contains("/openapi/")) {
            return OperationSource.OPENAPI;
        }
        return OperationSource.USER;
    }

    private AuthUserPrincipal getCurrentUser() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof AuthUserPrincipal) {
                return (AuthUserPrincipal) authentication.getPrincipal();
            }
        } catch (Exception exception) {
            log.debug("Failed to get current user", exception);
        }
        return null;
    }

    private String extractNamespace(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Parameter[] parameters = method.getParameters();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < parameters.length; i++) {
            PathVariable pathVariable = parameters[i].getAnnotation(PathVariable.class);
            if (pathVariable != null && "namespace".equals(pathVariable.value())) {
                return args[i] != null ? args[i].toString() : null;
            }
        }
        return null;
    }

    private String extractEnvironmentName(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        return request.getParameter("env");
    }

    private String extractResourceId(Object result, ProceedingJoinPoint joinPoint) {
        // Try to extract ID from result
        if (result != null) {
            try {
                // If result is Result<T>, extract data
                if (result.getClass().getSimpleName().equals("Result")) {
                    Object data = result.getClass().getMethod("data").invoke(result);
                    if (data != null) {
                        // Try to get id field
                        try {
                            Object id = data.getClass().getMethod("id").invoke(data);
                            return id != null ? id.toString() : null;
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception exception) {
                log.debug("Failed to extract resource ID from result", exception);
            }
        }

        // Try to extract from method parameters (e.g., @PathVariable name)
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Parameter[] parameters = method.getParameters();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < parameters.length; i++) {
            PathVariable pathVariable = parameters[i].getAnnotation(PathVariable.class);
            if (pathVariable != null && "name".equals(pathVariable.value())) {
                return args[i] != null ? args[i].toString() : null;
            }
            if (pathVariable != null && "id".equals(pathVariable.value())) {
                return args[i] != null ? args[i].toString() : null;
            }
        }

        return null;
    }

    private String buildDetails(ProceedingJoinPoint joinPoint, Object result) {
        try {
            Map<String, Object> details = new HashMap<>();

            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            Parameter[] parameters = method.getParameters();
            Object[] args = joinPoint.getArgs();

            // Include method parameters (excluding sensitive data)
            for (int i = 0; i < parameters.length; i++) {
                String paramName = parameters[i].getName();
                if (!paramName.contains("password") && !paramName.contains("secret") &&
                    !paramName.contains("token") && args[i] != null) {
                    details.put(paramName, args[i]);
                }
            }

            return objectMapper.writeValueAsString(details);
        } catch (Exception exception) {
            log.debug("Failed to build operation log details", exception);
            return null;
        }
    }
}
