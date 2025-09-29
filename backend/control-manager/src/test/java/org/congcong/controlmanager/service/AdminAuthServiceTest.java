package org.congcong.controlmanager.service;

import com.auth0.jwt.interfaces.DecodedJWT;
import org.congcong.controlmanager.config.AdminAuthProperties;
import org.congcong.controlmanager.dto.ChangePasswordRequest;
import org.congcong.controlmanager.dto.LoginRequest;
import org.congcong.controlmanager.dto.LoginResponse;
import org.congcong.controlmanager.entity.AdminLoginHistory;
import org.congcong.controlmanager.entity.AdminTokenBlacklist;
import org.congcong.controlmanager.entity.AdminUser;
import org.congcong.controlmanager.repository.AdminLoginHistoryRepository;
import org.congcong.controlmanager.repository.AdminTokenBlacklistRepository;
import org.congcong.controlmanager.repository.AdminUserRepository;
import org.congcong.controlmanager.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminAuthService 单元测试")
class AdminAuthServiceTest {

    @Mock
    private AdminUserRepository userRepo;
    
    @Mock
    private AdminLoginHistoryRepository historyRepo;
    
    @Mock
    private AdminTokenBlacklistRepository blacklistRepo;
    
    @Mock
    private PasswordEncoder encoder;
    
    @Mock
    private JwtService jwtService;
    
    @Mock
    private AdminAuthProperties props;
    
    @Mock
    private AdminAuthProperties.Jwt jwtProps;
    
    @InjectMocks
    private AdminAuthService adminAuthService;
    
    private AdminUser testUser;
    private LoginRequest loginRequest;
    private ChangePasswordRequest changePasswordRequest;
    
    @BeforeEach
    void setUp() {
        // 设置测试用户
        testUser = new AdminUser();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        testUser.setPasswordHash("$2a$11$hashedPassword");
        testUser.setRoles("ADMIN");
        testUser.setMustChangePassword(false);
        testUser.setVer(1);
        testUser.setStatus(1);
        testUser.setCreatedAt(LocalDateTime.now());
        testUser.setUpdatedAt(LocalDateTime.now());
        
        // 设置登录请求
        loginRequest = new LoginRequest();
        loginRequest.setUsername("testuser");
        loginRequest.setPassword("password123");
        
        // 设置修改密码请求
        changePasswordRequest = new ChangePasswordRequest();
        changePasswordRequest.setOldPassword("password123");
        changePasswordRequest.setNewPassword("newPassword123");
    }

    // ==================== 登录功能测试 ====================
    
    @Test
    @DisplayName("登录成功 - 正常用户")
    void testLoginSuccess() {
        // Given
        when(props.getJwt()).thenReturn(jwtProps);
        when(jwtProps.getTtlDays()).thenReturn(30);
        when(userRepo.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(encoder.matches("password123", testUser.getPasswordHash())).thenReturn(true);
        when(jwtService.issue(testUser)).thenReturn("mock-jwt-token");
        
        // When
        LoginResponse response = adminAuthService.login(loginRequest, "127.0.0.1", "Mozilla/5.0");
        
        // Then
        assertNotNull(response);
        assertEquals("mock-jwt-token", response.getToken());
        assertEquals(testUser.isMustChangePassword(), response.isMustChangePassword());
        assertEquals(30 * 24 * 3600L, response.getExpiresIn());
        assertNotNull(response.getUser());
        assertEquals(testUser.getId(), response.getUser().getId());
        assertEquals(testUser.getUsername(), response.getUser().getUsername());
        
        // 验证登录历史记录
        ArgumentCaptor<AdminLoginHistory> historyCaptor = ArgumentCaptor.forClass(AdminLoginHistory.class);
        verify(historyRepo).save(historyCaptor.capture());
        AdminLoginHistory savedHistory = historyCaptor.getValue();
        assertEquals(testUser.getId(), savedHistory.getUserId());
        assertEquals(testUser.getUsername(), savedHistory.getUsername());
        assertTrue(savedHistory.isSuccess());
        assertEquals("127.0.0.1", savedHistory.getIp());
        assertEquals("Mozilla/5.0", savedHistory.getUa());
        assertNull(savedHistory.getReason());
    }
    
    @Test
    @DisplayName("登录成功 - 首次登录用户需要修改密码")
    void testLoginSuccessWithMustChangePassword() {
        // Given
        when(props.getJwt()).thenReturn(jwtProps);
        when(jwtProps.getTtlDays()).thenReturn(30);
        testUser.setMustChangePassword(true);
        when(userRepo.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(encoder.matches("password123", testUser.getPasswordHash())).thenReturn(true);
        when(jwtService.issue(testUser)).thenReturn("mock-jwt-token");
        
        // When
        LoginResponse response = adminAuthService.login(loginRequest, "127.0.0.1", "Mozilla/5.0");
        
        // Then
        assertNotNull(response);
        assertTrue(response.isMustChangePassword());
        assertTrue(response.getUser().isMustChangePassword());
    }
    
    @Test
    @DisplayName("登录失败 - 用户不存在")
    void testLoginUserNotFound() {
        // Given
        when(userRepo.findByUsername("testuser")).thenReturn(Optional.empty());
        
        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, 
            () -> adminAuthService.login(loginRequest, "127.0.0.1", "Mozilla/5.0"));
        
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        assertEquals("bad credentials", exception.getReason());
        
        // 验证失败历史记录
        ArgumentCaptor<AdminLoginHistory> historyCaptor = ArgumentCaptor.forClass(AdminLoginHistory.class);
        verify(historyRepo).save(historyCaptor.capture());
        AdminLoginHistory savedHistory = historyCaptor.getValue();
        assertNull(savedHistory.getUserId());
        assertEquals("testuser", savedHistory.getUsername());
        assertFalse(savedHistory.isSuccess());
        assertEquals("bad_credentials", savedHistory.getReason());
    }
    
    @Test
    @DisplayName("登录失败 - 密码错误")
    void testLoginWrongPassword() {
        // Given
        when(userRepo.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(encoder.matches("password123", testUser.getPasswordHash())).thenReturn(false);
        
        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, 
            () -> adminAuthService.login(loginRequest, "127.0.0.1", "Mozilla/5.0"));
        
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        assertEquals("bad credentials", exception.getReason());
        
        // 验证失败历史记录
        verify(historyRepo).save(any(AdminLoginHistory.class));
    }
    
    @Test
    @DisplayName("登录失败 - 用户被禁用")
    void testLoginUserDisabled() {
        // Given
        testUser.setStatus(0); // 禁用状态
        when(userRepo.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        
        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, 
            () -> adminAuthService.login(loginRequest, "127.0.0.1", "Mozilla/5.0"));
        
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        assertEquals("bad credentials", exception.getReason());
    }

    // ==================== 修改密码功能测试 ====================
    
    @Test
    @DisplayName("修改密码成功 - 首次登录")
    void testChangePasswordFirstTime() {
        // Given
        when(props.getJwt()).thenReturn(jwtProps);
        when(jwtProps.getTtlDays()).thenReturn(30);
        testUser.setMustChangePassword(true);
        changePasswordRequest.setOldPassword(null); // 首次登录无需旧密码
        when(userRepo.findById(1L)).thenReturn(Optional.of(testUser));
        when(encoder.encode("newPassword123")).thenReturn("$2a$11$newHashedPassword");
        when(jwtService.issue(any(AdminUser.class))).thenReturn("new-jwt-token");
        
        // When
        LoginResponse response = adminAuthService.changePassword(testUser, changePasswordRequest);
        
        // Then
        assertNotNull(response);
        assertEquals("new-jwt-token", response.getToken());
        assertFalse(response.isMustChangePassword());
        assertFalse(response.getUser().isMustChangePassword());
        
        // 验证用户信息更新
        verify(userRepo).save(testUser);
        assertEquals("$2a$11$newHashedPassword", testUser.getPasswordHash());
        assertFalse(testUser.isMustChangePassword());
        assertEquals(2, testUser.getVer()); // 版本号递增
    }
    
    @Test
    @DisplayName("修改密码成功 - 普通修改")
    void testChangePasswordNormal() {
        // Given
        when(props.getJwt()).thenReturn(jwtProps);
        when(jwtProps.getTtlDays()).thenReturn(30);
        testUser.setMustChangePassword(false);
        when(userRepo.findById(1L)).thenReturn(Optional.of(testUser));
        when(encoder.matches("password123", testUser.getPasswordHash())).thenReturn(true);
        when(encoder.encode("newPassword123")).thenReturn("$2a$11$newHashedPassword");
        when(jwtService.issue(any(AdminUser.class))).thenReturn("new-jwt-token");
        
        // When
        LoginResponse response = adminAuthService.changePassword(testUser, changePasswordRequest);
        
        // Then
        assertNotNull(response);
        assertEquals("new-jwt-token", response.getToken());
        assertFalse(response.isMustChangePassword());
        
        // 验证版本号递增
        assertEquals(2, testUser.getVer());
    }
    
    @Test
    @DisplayName("修改密码失败 - 新密码长度不符合要求")
    void testChangePasswordInvalidLength() {
        // Given
        changePasswordRequest.setNewPassword("123"); // 长度不足8位
        when(userRepo.findById(1L)).thenReturn(Optional.of(testUser));
        
        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, 
            () -> adminAuthService.changePassword(testUser, changePasswordRequest));
        
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("invalid new password", exception.getReason());
    }
    
    @Test
    @DisplayName("修改密码失败 - 新密码长度超过限制")
    void testChangePasswordTooLong() {
        // Given
        changePasswordRequest.setNewPassword("a".repeat(65)); // 超过64位
        when(userRepo.findById(1L)).thenReturn(Optional.of(testUser));
        
        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, 
            () -> adminAuthService.changePassword(testUser, changePasswordRequest));
        
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("invalid new password", exception.getReason());
    }
    
    @Test
    @DisplayName("修改密码失败 - 新密码与旧密码相同")
    void testChangePasswordSameAsOld() {
        // Given
        changePasswordRequest.setOldPassword("password123");
        changePasswordRequest.setNewPassword("password123"); // 与旧密码相同
        when(userRepo.findById(1L)).thenReturn(Optional.of(testUser));
        
        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, 
            () -> adminAuthService.changePassword(testUser, changePasswordRequest));
        
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("old and new are same", exception.getReason());
    }
    
    @Test
    @DisplayName("修改密码失败 - 旧密码错误")
    void testChangePasswordWrongOldPassword() {
        // Given
        testUser.setMustChangePassword(false);
        when(userRepo.findById(1L)).thenReturn(Optional.of(testUser));
        when(encoder.matches("password123", testUser.getPasswordHash())).thenReturn(false);
        
        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, 
            () -> adminAuthService.changePassword(testUser, changePasswordRequest));
        
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.getStatusCode());
        assertEquals("old password incorrect", exception.getReason());
    }
    
    @Test
    @DisplayName("修改密码失败 - 用户不存在")
    void testChangePasswordUserNotFound() {
        // Given
        when(userRepo.findById(1L)).thenReturn(Optional.empty());
        
        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, 
            () -> adminAuthService.changePassword(testUser, changePasswordRequest));
        
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
    }

    // ==================== 登出功能测试 ====================
    
    @Test
    @DisplayName("登出成功 - token加入黑名单")
    void testLogoutSuccess() {
        // Given
        String token = "valid-jwt-token";
        DecodedJWT mockJwt = mock(DecodedJWT.class);
        when(mockJwt.getId()).thenReturn("jwt-id-123");
        Date expDate = new Date(System.currentTimeMillis() + 86400000); // 1天后过期
        when(mockJwt.getExpiresAt()).thenReturn(expDate);
        when(jwtService.verify(token)).thenReturn(mockJwt);
        
        // When
        adminAuthService.logout(token);
        
        // Then
        ArgumentCaptor<AdminTokenBlacklist> blacklistCaptor = ArgumentCaptor.forClass(AdminTokenBlacklist.class);
        verify(blacklistRepo).save(blacklistCaptor.capture());
        AdminTokenBlacklist savedBlacklist = blacklistCaptor.getValue();
        assertEquals("jwt-id-123", savedBlacklist.getJti());
        assertNotNull(savedBlacklist.getExpiresAt());
    }
    
    @Test
    @DisplayName("登出失败 - 无效token")
    void testLogoutInvalidToken() {
        // Given
        String token = "invalid-jwt-token";
        when(jwtService.verify(token)).thenThrow(new RuntimeException("Invalid token"));
        
        // When & Then
        assertThrows(RuntimeException.class, () -> adminAuthService.logout(token));
        
        // 验证没有保存到黑名单
        verify(blacklistRepo, never()).save(any(AdminTokenBlacklist.class));
    }
}