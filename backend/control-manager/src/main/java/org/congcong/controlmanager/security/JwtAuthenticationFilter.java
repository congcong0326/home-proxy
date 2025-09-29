package org.congcong.controlmanager.security;

import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.congcong.controlmanager.entity.AdminTokenBlacklist;
import org.congcong.controlmanager.entity.AdminUser;
import org.congcong.controlmanager.repository.AdminTokenBlacklistRepository;
import org.congcong.controlmanager.repository.AdminUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final AdminUserRepository userRepo;
    private final AdminTokenBlacklistRepository blacklistRepo;

    public JwtAuthenticationFilter(JwtService jwtService, AdminUserRepository userRepo, AdminTokenBlacklistRepository blacklistRepo) {
        this.jwtService = jwtService;
        this.userRepo = userRepo;
        this.blacklistRepo = blacklistRepo;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if ("/admin/login".equals(path)) { chain.doFilter(request, response); return; }
        String auth = Optional.ofNullable(request.getHeader("Authorization")).orElse("");
        if (!auth.startsWith("Bearer ")) { unauthorized(response, "UNAUTHORIZED", "invalid or expired token"); return; }
        String token = auth.substring(7);
        try {
            DecodedJWT jwt = jwtService.verify(token);
            // 黑名单校验
            if (blacklistRepo != null) {
                Optional<AdminTokenBlacklist> bl = blacklistRepo.findById(jwt.getId());
                if (bl.isPresent() && bl.get().getExpiresAt().isAfter(LocalDateTime.now())) {
                    unauthorized(response, "UNAUTHORIZED", "invalid or expired token");
                    return;
                }
            }
            Long userId = Long.valueOf(jwt.getSubject());
            AdminUser user = userRepo.findById(userId).orElse(null);
            if (user == null || user.getStatus() != 1 || user.getVer() != jwt.getClaim("ver").asInt()) {
                unauthorized(response, "UNAUTHORIZED", "invalid or expired token");
                return;
            }
            boolean mustChange = user.isMustChangePassword();
            if (mustChange && !"/admin/change-password".equals(path)) {
                forbidden(response, "FORCE_PASSWORD_CHANGE", "please change password first");
                return;
            }
            List<SimpleGrantedAuthority> auths = new ArrayList<>();
            String[] roles = jwt.getClaim("roles").asArray(String.class);
            if (roles != null) for (String r : roles) auths.add(new SimpleGrantedAuthority("ROLE_" + r));
            Authentication authentication = new UsernamePasswordAuthenticationToken(user, null, auths);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            chain.doFilter(request, response);
        } catch (Exception e) {
            unauthorized(response, "UNAUTHORIZED", "invalid or expired token");
        }
    }

    private void unauthorized(HttpServletResponse response, String code, String msg) throws IOException {
        String rid = response.getHeader("X-Request-Id");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(String.format("{\"code\":\"%s\",\"message\":\"%s\",\"requestId\":\"%s\"}", code, msg, rid));
    }
    private void forbidden(HttpServletResponse response, String code, String msg) throws IOException {
        String rid = response.getHeader("X-Request-Id");
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(String.format("{\"code\":\"%s\",\"message\":\"%s\",\"requestId\":\"%s\"}", code, msg, rid));
    }
}