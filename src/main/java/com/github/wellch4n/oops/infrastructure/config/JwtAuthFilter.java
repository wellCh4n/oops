package com.github.wellch4n.oops.infrastructure.config;

import com.github.wellch4n.oops.application.port.repository.UserRepository;
import com.github.wellch4n.oops.domain.identity.User;
import com.github.wellch4n.oops.interfaces.dto.AuthUserPrincipal;
import com.github.wellch4n.oops.shared.util.JwtUtils;
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

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final UserRepository userRepository;

    public JwtAuthFilter(JwtUtils jwtUtils, UserRepository userRepository) {
        this.jwtUtils = jwtUtils;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String token = null;
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7);
        } else {
            String queryToken = request.getParameter("token");
            if (queryToken != null && !queryToken.isBlank()) {
                token = queryToken;
            }
        }
        if (token != null && jwtUtils.isValid(token)) {
            String username = jwtUtils.getUsername(token);
            String userId = jwtUtils.getUserId(token);
            if (userId == null || userId.isBlank()) {
                userId = userRepository.findByUsername(username).map(User::getId).orElse(null);
            }
            String role = jwtUtils.getRole(token);
            AuthUserPrincipal principal = new AuthUserPrincipal(userId, username);
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))
            );
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        filterChain.doFilter(request, response);
    }
}
