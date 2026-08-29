package com.github.wellch4n.oops.infrastructure.config;

import com.github.wellch4n.oops.application.port.repository.UserRepository;
import com.github.wellch4n.oops.domain.identity.User;
import com.github.wellch4n.oops.interfaces.dto.AuthUserPrincipal;
import com.github.wellch4n.oops.interfaces.rest.OpenApiHidden;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Component
public class OpenApiAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final UserRepository userRepository;
    private final RequestMappingHandlerMapping handlerMapping;

    public OpenApiAuthFilter(UserRepository userRepository,
                             RequestMappingHandlerMapping handlerMapping) {
        this.userRepository = userRepository;
        this.handlerMapping = handlerMapping;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/openapi/");
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        User user = userRepository.findByAccessToken(token).orElse(null);
        if (user == null || Boolean.FALSE.equals(user.getEnabled())) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (isHiddenFromOpenApi(request)) {
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        AuthUserPrincipal principal = new AuthUserPrincipal(user.getId(), user.getUsername());
        String role = user.getRole() != null ? user.getRole().name() : "USER";
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
        filterChain.doFilter(request, response);
    }

    private boolean isHiddenFromOpenApi(HttpServletRequest request) {
        try {
            HandlerExecutionChain chain = handlerMapping.getHandler(request);
            if (chain == null) {
                return false;
            }
            if (!(chain.getHandler() instanceof HandlerMethod handlerMethod)) {
                return false;
            }
            return handlerMethod.hasMethodAnnotation(OpenApiHidden.class);
        } catch (Exception exception) {
            return false;
        }
    }
}
