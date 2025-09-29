package org.congcong.controlmanager.security;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.congcong.controlmanager.entity.AdminTokenBlacklist;
import org.congcong.controlmanager.entity.AdminUser;
import org.congcong.controlmanager.repository.AdminTokenBlacklistRepository;
import org.congcong.controlmanager.repository.AdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter 单元测试")
class JwtAuthenticationFilterTest {

    @Mock
    private JwtService jwtService;
    
    @Mock
    private AdminUserRepository userRepo;
    
    @Mock
    private AdminTokenBlacklistRepository blacklistRepo;
    
    @Mock
    private HttpServletRequest request;
    
    @Mock
    private HttpServletResponse response;
    
    @Mock
    private FilterChain filterChain;
    
    @Mock
    private DecodedJWT decodedJWT;
    
    @Mock
    private PrintWriter writer;
    
    @Mock
    private Claim claim;
    
    private JwtAuthenticationFilter filter;
    private AdminUser testUser;
    
    @BeforeEach
    void setUp() throws IOException {
        filter = new JwtAuthenticationFilter(jwtService, userRepo, blacklistRepo);
        
        // 设置测试用户
        testUser = new AdminUser();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        testUser.setRoles("ADMIN");
        testUser.setMustChangePassword(false);
        testUser.setVer(1);
        testUser.setStatus(1);
        testUser.setCreatedAt(LocalDateTime.now());
        testUser.setUpdatedAt(LocalDateTime.now());
        
        // 清理 SecurityContext
        SecurityContextHolder.clearContext();
        
        // Mock response writer
        lenient().when(response.getWriter()).thenReturn(writer);
        lenient().when(response.getHeader("X-Request-Id")).thenReturn("test-request-id");
    }

    @Test
    @DisplayName("有效token通过认证")
    void testFilterValidToken() throws ServletException, IOException {
        // Given
        when(request.getRequestURI()).thenReturn("/admin/users");
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
        when(jwtService.verify("valid-token")).thenReturn(decodedJWT);
        when(decodedJWT.getId()).thenReturn("jwt-id-123");
        when(decodedJWT.getSubject()).thenReturn("1");
        when(decodedJWT.getClaim("ver")).thenReturn(claim);
        when(claim.asInt()).thenReturn(1);
        when(decodedJWT.getClaim("roles")).thenReturn(claim);
        when(claim.asArray(String.class)).thenReturn(new String[]{"ADMIN"});
        
        when(blacklistRepo.findById("jwt-id-123")).thenReturn(Optional.empty());
        when(userRepo.findById(1L)).thenReturn(Optional.of(testUser));
        
        // When
        filter.doFilterInternal(request, response, filterChain);
        
        // Then
        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
        
        // 验证 SecurityContext 中设置了认证信息
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals(testUser, auth.getPrincipal());
        assertTrue(auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
    }
    
    @Test
    @DisplayName("无效token被拒绝")
    void testFilterInvalidToken() throws ServletException, IOException {
        // Given
        when(request.getRequestURI()).thenReturn("/admin/users");
        when(request.getHeader("Authorization")).thenReturn("Bearer invalid-token");
        when(jwtService.verify("invalid-token")).thenThrow(new JWTVerificationException("Invalid token"));
        
        // When
        filter.doFilterInternal(request, response, filterChain);
        
        // Then
        verify(filterChain, never()).doFilter(request, response);
        verify(response).setStatus(HttpStatus.UNAUTHORIZED.value());
        verify(response).setContentType("application/json;charset=UTF-8");
        verify(writer).write(contains("UNAUTHORIZED"));
        verify(writer).write(contains("invalid or expired token"));
        
        // 验证 SecurityContext 没有设置认证信息
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNull(auth);
    }
    
    @Test
    @DisplayName("过期token被拒绝")
    void testFilterExpiredToken() throws ServletException, IOException {
        // Given
        when(request.getRequestURI()).thenReturn("/admin/users");
        when(request.getHeader("Authorization")).thenReturn("Bearer expired-token");
        when(jwtService.verify("expired-token")).thenThrow(new JWTVerificationException("Token expired"));
        
        // When
        filter.doFilterInternal(request, response, filterChain);
        
        // Then
        verify(filterChain, never()).doFilter(request, response);
        verify(response).setStatus(HttpStatus.UNAUTHORIZED.value());
        verify(response).setContentType("application/json;charset=UTF-8");
        verify(writer).write(contains("UNAUTHORIZED"));
        verify(writer).write(contains("invalid or expired token"));
        
        // 验证 SecurityContext 没有设置认证信息
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNull(auth);
    }
    
    @Test
    @DisplayName("黑名单token被拒绝")
    void testFilterBlacklistedToken() throws ServletException, IOException {
        // Given
        when(request.getRequestURI()).thenReturn("/admin/users");
        when(request.getHeader("Authorization")).thenReturn("Bearer blacklisted-token");
        when(jwtService.verify("blacklisted-token")).thenReturn(decodedJWT);
        when(decodedJWT.getId()).thenReturn("jwt-id-123");
        
        AdminTokenBlacklist blacklistEntry = new AdminTokenBlacklist();
        blacklistEntry.setJti("jwt-id-123");
        blacklistEntry.setExpiresAt(LocalDateTime.now().plusDays(1)); // 未过期的黑名单条目
        
        when(blacklistRepo.findById("jwt-id-123")).thenReturn(Optional.of(blacklistEntry));
        
        // When
        filter.doFilterInternal(request, response, filterChain);
        
        // Then
        verify(filterChain, never()).doFilter(request, response);
        verify(response).setStatus(HttpStatus.UNAUTHORIZED.value());
        verify(response).setContentType("application/json;charset=UTF-8");
        verify(writer).write(contains("UNAUTHORIZED"));
        verify(writer).write(contains("invalid or expired token"));
        
        // 验证 SecurityContext 没有设置认证信息
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNull(auth);
    }
    
    @Test
    @DisplayName("版本号不匹配被拒绝")
    void testFilterVersionMismatch() throws ServletException, IOException {
        // Given
        when(request.getRequestURI()).thenReturn("/admin/users");
        when(request.getHeader("Authorization")).thenReturn("Bearer version-mismatch-token");
        when(jwtService.verify("version-mismatch-token")).thenReturn(decodedJWT);
        when(decodedJWT.getId()).thenReturn("jwt-id-123");
        when(decodedJWT.getSubject()).thenReturn("1");
        when(decodedJWT.getClaim("ver")).thenReturn(claim);
        when(claim.asInt()).thenReturn(2); // token中的版本号为2
        
        when(blacklistRepo.findById("jwt-id-123")).thenReturn(Optional.empty());
        
        // 用户的版本号为1，与token中的版本号不匹配
        testUser.setVer(1);
        when(userRepo.findById(1L)).thenReturn(Optional.of(testUser));
        
        // When
        filter.doFilterInternal(request, response, filterChain);
        
        // Then
        verify(filterChain, never()).doFilter(request, response);
        verify(response).setStatus(HttpStatus.UNAUTHORIZED.value());
        verify(response).setContentType("application/json;charset=UTF-8");
        verify(writer).write(contains("UNAUTHORIZED"));
        verify(writer).write(contains("invalid or expired token"));
        
        // 验证 SecurityContext 没有设置认证信息
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNull(auth);
    }
}