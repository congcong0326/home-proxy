package org.congcong.controlmanager.security;

import org.congcong.controlmanager.repository.AdminTokenBlacklistRepository;
import org.congcong.controlmanager.repository.AdminUserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(11); }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService, AdminUserRepository userRepo,
                                           AdminTokenBlacklistRepository blacklistRepo,
                                           WorkerTokenAuthenticationService workerTokenAuthenticationService,
                                           @Value("${worker.auth.legacy-enabled:false}") boolean legacyEnabled) throws Exception {
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService, userRepo, blacklistRepo);
        WorkerTokenAuthenticationFilter workerFilter = new WorkerTokenAuthenticationFilter(workerTokenAuthenticationService, legacyEnabled);
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/admin/login").permitAll()
                    .requestMatchers(HttpMethod.GET, "/admin/setup-status").permitAll()
                    .requestMatchers(HttpMethod.POST, "/admin/setup").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/config/aggregate").hasRole("WORKER")
                    .requestMatchers(HttpMethod.POST, "/api/worker/poll").hasRole("WORKER")
                    .requestMatchers(HttpMethod.POST, "/api/logs/access", "/api/logs/auth").hasRole("WORKER")
                    .requestMatchers(HttpMethod.GET, "/api/logs/**").hasRole("ADMIN")
                    .requestMatchers("/api/config/hash").hasRole("ADMIN")
                    .requestMatchers("/api/worker/status").hasRole("ADMIN")
                    .requestMatchers("/api/workers/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                    .requestMatchers(HttpMethod.POST, "/api/disk/push").permitAll()
                    .requestMatchers("/error").permitAll()
                    // 允许访问静态资源
                    .requestMatchers("/", "/static/**", "/favicon.ico", "/manifest.json", "/robots.txt").permitAll()
                    .requestMatchers("/*.js", "/*.css", "/*.png", "/*.jpg", "/*.jpeg", "/*.gif", "/*.svg", "/*.ico").permitAll()
                    // 允许访问前端路由页面
                    .requestMatchers("/login", "/setup", "/dashboard", "/users", "/routes", "/inbound", "/ratelimit", "/logs", "/analysis", "/config", "/config/**", "/change-password").permitAll()
                    .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> {
                    res.setStatus(HttpStatus.UNAUTHORIZED.value());
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"invalid or expired token\"}");
                })
                .accessDeniedHandler((req, res, e) -> {
                    res.setStatus(HttpStatus.FORBIDDEN.value());
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"access denied\"}");
                })
            )
            .addFilterBefore(workerFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .cors(Customizer.withDefaults());
        return http.build();
    }
}
