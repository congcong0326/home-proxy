package org.congcong.controlmanager.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.congcong.controlmanager.config.AdminAuthProperties;
import org.congcong.controlmanager.dto.LoginResponse;
import org.congcong.controlmanager.dto.SetupAdminRequest;
import org.congcong.controlmanager.entity.AdminUser;
import org.congcong.controlmanager.enums.AdminRole;
import org.congcong.controlmanager.repository.AdminLoginHistoryRepository;
import org.congcong.controlmanager.repository.AdminTokenBlacklistRepository;
import org.congcong.controlmanager.repository.AdminUserRepository;
import org.congcong.controlmanager.security.JwtService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

class AdminAuthServiceTest {

    private final AdminUserRepository userRepository = mock(AdminUserRepository.class);
    private final AdminLoginHistoryRepository historyRepository = mock(AdminLoginHistoryRepository.class);
    private final AdminTokenBlacklistRepository blacklistRepository = mock(AdminTokenBlacklistRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final AdminAuthService authService = new AdminAuthService(
            userRepository,
            historyRepository,
            blacklistRepository,
            passwordEncoder,
            new JwtService(authProperties()),
            authProperties());

    @Test
    void setupIsRequiredWhenNoAdminExists() {
        when(userRepository.count()).thenReturn(0L);

        assertTrue(authService.isSetupRequired());
    }

    @Test
    void setupIsNotRequiredWhenAdminExists() {
        when(userRepository.count()).thenReturn(1L);

        assertFalse(authService.isSetupRequired());
    }

    @Test
    void setupCreatesFirstSuperAdminAndReturnsLoginToken() {
        when(userRepository.count()).thenReturn(0L);
        when(userRepository.save(any(AdminUser.class))).thenAnswer(invocation -> {
            AdminUser user = invocation.getArgument(0);
            user.setId(1L);
            return user;
        });
        SetupAdminRequest request = new SetupAdminRequest();
        request.setUsername("owner");
        request.setPassword("changeMe123");

        LoginResponse response = authService.setupFirstAdmin(request);

        ArgumentCaptor<AdminUser> userCaptor = ArgumentCaptor.forClass(AdminUser.class);
        verify(userRepository).save(userCaptor.capture());
        AdminUser savedUser = userCaptor.getValue();
        assertEquals("owner", savedUser.getUsername());
        assertTrue(passwordEncoder.matches("changeMe123", savedUser.getPasswordHash()));
        assertEquals(AdminRole.SUPER_ADMIN.getCode(), savedUser.getRoles());
        assertFalse(savedUser.isMustChangePassword());
        assertEquals(1, savedUser.getStatus());
        assertNotNull(response.getToken());
        assertEquals(86400L, response.getExpiresIn());
        assertEquals("owner", response.getUser().getUsername());
        assertEquals(AdminRole.SUPER_ADMIN.getCode(), response.getUser().getRoles().get(0));
        assertFalse(response.isMustChangePassword());
    }

    @Test
    void setupRejectsWhenAnyAdminAlreadyExists() {
        when(userRepository.count()).thenReturn(1L);
        SetupAdminRequest request = new SetupAdminRequest();
        request.setUsername("owner");
        request.setPassword("changeMe123");

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> authService.setupFirstAdmin(request));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(userRepository, never()).save(any(AdminUser.class));
    }

    private AdminAuthProperties authProperties() {
        AdminAuthProperties properties = new AdminAuthProperties();
        properties.getJwt().setSecret("test_secret_key_for_setup_tests_only");
        properties.getJwt().setTtlDays(1);
        properties.getJwt().setIssuer("setup-test");
        return properties;
    }
}
