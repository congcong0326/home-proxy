package org.congcong.controlmanager.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class WorkerTokenAuthenticationFilter extends OncePerRequestFilter {
    private final WorkerTokenAuthenticationService authenticationService;
    private final boolean legacyEnabled;

    public WorkerTokenAuthenticationFilter(WorkerTokenAuthenticationService authenticationService,
                                           @Value("${worker.auth.legacy-enabled:false}") boolean legacyEnabled) {
        this.authenticationService = authenticationService;
        this.legacyEnabled = legacyEnabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !("/api/config/aggregate".equals(path)
                || "/api/worker/poll".equals(path)
                || "/api/logs/access".equals(path)
                || "/api/logs/auth".equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring("Bearer ".length()).trim();
            java.util.Optional<org.congcong.controlmanager.entity.ProxyWorker> worker = authenticationService.findWorker(token);
            if (worker.isEmpty()) {
                unauthorized(response);
                return;
            }
            if (!Boolean.TRUE.equals(worker.get().getEnabled())) {
                forbidden(response);
                return;
            }
            WorkerPrincipal principal = new WorkerPrincipal(worker.get().getWorkerId(), worker.get().getDisplayName());
            {
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_WORKER")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
            chain.doFilter(request, response);
            return;
        }

        if (legacyEnabled) {
            WorkerPrincipal principal = new WorkerPrincipal("default", "default");
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority("ROLE_WORKER"))));
            chain.doFilter(request, response);
            return;
        }
        unauthorized(response);
    }

    private void unauthorized(HttpServletResponse response) {
        try {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"invalid worker token\"}");
        } catch (IOException ignored) {
            // The container will terminate the response if the client disconnected.
        }
    }

    private void forbidden(HttpServletResponse response) {
        try {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"worker is disabled\"}");
        } catch (IOException ignored) {
            // The container will terminate the response if the client disconnected.
        }
    }
}
