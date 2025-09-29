package org.congcong.controlmanager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.congcong.controlmanager.dto.ChangePasswordRequest;
import org.congcong.controlmanager.dto.CreateAdminRequest;
import org.congcong.controlmanager.dto.DeleteAdminRequest;
import org.congcong.controlmanager.dto.DisableAdminRequest;
import org.congcong.controlmanager.dto.LoginRequest;
import org.congcong.controlmanager.entity.AdminLoginHistory;
import org.congcong.controlmanager.entity.AdminTokenBlacklist;
import org.congcong.controlmanager.entity.AdminUser;
import org.congcong.controlmanager.repository.AdminLoginHistoryRepository;
import org.congcong.controlmanager.repository.AdminTokenBlacklistRepository;
import org.congcong.controlmanager.repository.AdminUserRepository;
import org.congcong.controlmanager.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class AdminControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AdminUserRepository userRepository;

    @Autowired
    private AdminLoginHistoryRepository loginHistoryRepository;

    @Autowired
    private AdminTokenBlacklistRepository blacklistRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private AdminUser testUser;
    private AdminUser firstTimeUser;
    private AdminUser disabledUser;
    private AdminUser superAdminUser;

    @BeforeEach
    void setUp() {
        // 设置MockMvc
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        // 清理数据
        blacklistRepository.deleteAll();
        loginHistoryRepository.deleteAll();
        userRepository.deleteAll();

        // 创建测试用户
        testUser = new AdminUser();
        testUser.setUsername("testuser");
        testUser.setPasswordHash(passwordEncoder.encode("password123"));
        testUser.setRoles("ADMIN");
        testUser.setMustChangePassword(false);
        testUser.setVer(1);
        testUser.setStatus(1);
        testUser.setCreatedAt(LocalDateTime.now());
        testUser.setUpdatedAt(LocalDateTime.now());
        testUser = userRepository.save(testUser);

        // 创建首次登录用户
        firstTimeUser = new AdminUser();
        firstTimeUser.setUsername("firsttime");
        firstTimeUser.setPasswordHash(passwordEncoder.encode("password123"));
        firstTimeUser.setRoles("USER");
        firstTimeUser.setMustChangePassword(true);
        firstTimeUser.setVer(1);
        firstTimeUser.setStatus(1);
        firstTimeUser.setCreatedAt(LocalDateTime.now());
        firstTimeUser.setUpdatedAt(LocalDateTime.now());
        firstTimeUser = userRepository.save(firstTimeUser);

        // 创建禁用用户
        disabledUser = new AdminUser();
        disabledUser.setUsername("disabled");
        disabledUser.setPasswordHash(passwordEncoder.encode("password123"));
        disabledUser.setRoles("USER");
        disabledUser.setMustChangePassword(false);
        disabledUser.setVer(1);
        disabledUser.setStatus(0); // 禁用状态
        disabledUser.setCreatedAt(LocalDateTime.now());
        disabledUser.setUpdatedAt(LocalDateTime.now());
        disabledUser = userRepository.save(disabledUser);

        // 创建超级管理员用户
        superAdminUser = new AdminUser();
        superAdminUser.setUsername("superadmin");
        superAdminUser.setPasswordHash(passwordEncoder.encode("password123"));
        superAdminUser.setRoles("SUPER_ADMIN");
        superAdminUser.setMustChangePassword(false);
        superAdminUser.setVer(1);
        superAdminUser.setStatus(1);
        superAdminUser.setCreatedAt(LocalDateTime.now());
        superAdminUser.setUpdatedAt(LocalDateTime.now());
        superAdminUser = userRepository.save(superAdminUser);
    }

    // ========== 登录接口测试 ==========

    @Test
    void testLoginEndpointSuccess() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("testuser");
        request.setPassword("password123");

        mockMvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("User-Agent", "Test-Agent")
                        .with(req -> {
                            req.setRemoteAddr("192.168.1.100");
                            return req;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                .andExpect(jsonPath("$.expiresIn").isNumber())
                .andExpect(jsonPath("$.user.id").value(testUser.getId()))
                .andExpect(jsonPath("$.user.username").value("testuser"))
                .andExpect(jsonPath("$.user.roles").isArray())
                .andExpect(jsonPath("$.user.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.user.mustChangePassword").value(false));

        // 验证登录历史记录
        List<AdminLoginHistory> histories = loginHistoryRepository.findAll();
        assertEquals(1, histories.size());
        AdminLoginHistory history = histories.get(0);
        assertEquals(testUser.getId(), history.getUserId());
        assertEquals("testuser", history.getUsername());
        assertTrue(history.isSuccess());
        assertEquals("192.168.1.100", history.getIp());
        assertEquals("Test-Agent", history.getUa());
        assertNull(history.getReason());
    }

    @Test
    void testLoginEndpointBadCredentials() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("testuser");
        request.setPassword("wrongpassword");

        mockMvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("bad credentials"));

        // 验证失败的登录历史记录
        List<AdminLoginHistory> histories = loginHistoryRepository.findAll();
        assertEquals(1, histories.size());
        AdminLoginHistory history = histories.get(0);
        assertEquals(testUser.getId(), history.getUserId());
        assertEquals("testuser", history.getUsername());
        assertFalse(history.isSuccess());
        assertEquals("bad_credentials", history.getReason());
    }

    @Test
    void testLoginEndpointValidationError() throws Exception {
        // 测试用户名为空
        LoginRequest request = new LoginRequest();
        request.setUsername("");
        request.setPassword("password123");

        mockMvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        // 测试密码太短
        request.setUsername("testuser");
        request.setPassword("123");

        mockMvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        // 测试用户名包含非法字符
        request.setUsername("test@user");
        request.setPassword("password123");

        mockMvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testLoginEndpointRecordClientInfo() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("testuser");
        request.setPassword("password123");

        mockMvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .with(req -> {
                            req.setRemoteAddr("10.0.0.1");
                            return req;
                        }))
                .andExpect(status().isOk());

        // 验证IP和User-Agent记录
        List<AdminLoginHistory> histories = loginHistoryRepository.findAll();
        assertEquals(1, histories.size());
        AdminLoginHistory history = histories.get(0);
        assertEquals("10.0.0.1", history.getIp());
        assertEquals("Mozilla/5.0 (Windows NT 10.0; Win64; x64)", history.getUa());
    }

    // ========== 修改密码接口测试 ==========

    @Test
    void testChangePasswordEndpointSuccess() throws Exception {
        String token = jwtService.issue(testUser);

        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("password123");
        request.setNewPassword("newpassword123");

        mockMvc.perform(post("/admin/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                .andExpect(jsonPath("$.user.mustChangePassword").value(false));

        // 验证密码已更改
        AdminUser updatedUser = userRepository.findById(testUser.getId()).orElseThrow();
        assertTrue(passwordEncoder.matches("newpassword123", updatedUser.getPasswordHash()));
        assertFalse(updatedUser.isMustChangePassword());
        assertEquals(2, updatedUser.getVer()); // 版本号应该增加
    }

    @Test
    void testChangePasswordEndpointUnauthorized() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("password123");
        request.setNewPassword("newpassword123");

        // 无token访问
        mockMvc.perform(post("/admin/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        // 无效token访问
        mockMvc.perform(post("/admin/change-password")
                        .header("Authorization", "Bearer invalid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testChangePasswordEndpointFirstTime() throws Exception {
        String token = jwtService.issue(firstTimeUser);

        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setNewPassword("newpassword123");

        mockMvc.perform(post("/admin/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                .andExpect(jsonPath("$.user.mustChangePassword").value(false));
    }

    @Test
    void testChangePasswordEndpointValidationError() throws Exception {
        String token = jwtService.issue(testUser);

        // 测试新密码太短
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("password123");
        request.setNewPassword("123");

        mockMvc.perform(post("/admin/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        // 测试新密码太长
        request.setNewPassword("a".repeat(65));

        mockMvc.perform(post("/admin/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        // 测试新密码为空
        request.setNewPassword("");

        mockMvc.perform(post("/admin/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // ========== 获取用户信息接口测试 ==========

    @Test
    void testMeEndpointSuccess() throws Exception {
        String token = jwtService.issue(testUser);

        mockMvc.perform(get("/admin/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(testUser.getId()))
                .andExpect(jsonPath("$.username").value("testuser"))
                .andExpect(jsonPath("$.roles").isArray())
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.mustChangePassword").value(false));
    }

    @Test
    void testMeEndpointUnauthorized() throws Exception {
        // 无token访问
        mockMvc.perform(get("/admin/me"))
                .andExpect(status().isUnauthorized());

        // 无效token访问
        mockMvc.perform(get("/admin/me")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testMeEndpointRolesMapping() throws Exception {
        // 创建多角色用户
        AdminUser multiRoleUser = new AdminUser();
        multiRoleUser.setUsername("multirole");
        multiRoleUser.setPasswordHash(passwordEncoder.encode("password123"));
        multiRoleUser.setRoles("ADMIN,USER,MANAGER");
        multiRoleUser.setMustChangePassword(false);
        multiRoleUser.setVer(1);
        multiRoleUser.setStatus(1);
        multiRoleUser.setCreatedAt(LocalDateTime.now());
        multiRoleUser.setUpdatedAt(LocalDateTime.now());
        multiRoleUser = userRepository.save(multiRoleUser);

        String token = jwtService.issue(multiRoleUser);

        mockMvc.perform(get("/admin/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").isArray())
                .andExpect(jsonPath("$.roles", hasSize(3)))
                .andExpect(jsonPath("$.roles", containsInAnyOrder("ADMIN", "USER", "MANAGER")));

        // 测试空角色
        AdminUser noRoleUser = new AdminUser();
        noRoleUser.setUsername("norole");
        noRoleUser.setPasswordHash(passwordEncoder.encode("password123"));
        noRoleUser.setRoles("");
        noRoleUser.setMustChangePassword(false);
        noRoleUser.setVer(1);
        noRoleUser.setStatus(1);
        noRoleUser.setCreatedAt(LocalDateTime.now());
        noRoleUser.setUpdatedAt(LocalDateTime.now());
        noRoleUser = userRepository.save(noRoleUser);

        String noRoleToken = jwtService.issue(noRoleUser);

        mockMvc.perform(get("/admin/me")
                        .header("Authorization", "Bearer " + noRoleToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").isArray())
                .andExpect(jsonPath("$.roles", hasSize(0)));
    }

    // ========== 登出接口测试 ==========

    @Test
    void testLogoutEndpointSuccess() throws Exception {
        String token = jwtService.issue(testUser);

        mockMvc.perform(post("/admin/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        // 验证token已加入黑名单
        List<AdminTokenBlacklist> blacklists = blacklistRepository.findAll();
        assertEquals(1, blacklists.size());
        assertNotNull(blacklists.get(0).getJti());
        assertNotNull(blacklists.get(0).getExpiresAt());
    }

    @Test
    void testLogoutEndpointWithoutToken() throws Exception {
        // 无token登出仍返回成功
        mockMvc.perform(post("/admin/logout"))
                .andExpect(status().is(HttpStatus.UNAUTHORIZED.value()));

        // 验证没有token加入黑名单
        List<AdminTokenBlacklist> blacklists = blacklistRepository.findAll();
        assertEquals(0, blacklists.size());
    }

    // ========== 首次登录强制修改密码测试 ==========

    @Test
    void testFirstTimeLoginForcePasswordChange() throws Exception {
        String token = jwtService.issue(firstTimeUser);

        // 首次登录用户访问/admin/me应该被拒绝
        mockMvc.perform(get("/admin/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORCE_PASSWORD_CHANGE"))
                .andExpect(jsonPath("$.message").value("please change password first"));

        // 但可以访问修改密码接口
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setNewPassword("newpassword123");

        mockMvc.perform(post("/admin/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    // ========== 禁用用户测试 ==========

    @Test
    void testDisabledUserLogin() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("disabled");
        request.setPassword("password123");

        mockMvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("bad credentials"));
    }

    // ========== 管理员管理接口测试 ==========

    @Test
    void testCreateAdminEndpointSuccess() throws Exception {
        String token = jwtService.issue(superAdminUser);

        CreateAdminRequest request = new CreateAdminRequest();
        request.setUsername("newadmin");
        request.setPassword("newpassword123");
        request.setRoles("ADMIN");

        mockMvc.perform(post("/admin/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("newadmin"))
                .andExpect(jsonPath("$.roles").isArray())
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.mustChangePassword").value(true));

        // 验证用户已创建
        AdminUser createdUser = userRepository.findByUsername("newadmin").orElse(null);
        assertNotNull(createdUser);
        assertEquals("newadmin", createdUser.getUsername());
        assertEquals("ADMIN", createdUser.getRoles());
        assertTrue(createdUser.isMustChangePassword());
        assertEquals(1, createdUser.getStatus());
    }

    @Test
    void testCreateAdminEndpointUnauthorized() throws Exception {
        String token = jwtService.issue(testUser); // 普通管理员

        CreateAdminRequest request = new CreateAdminRequest();
        request.setUsername("newadmin");
        request.setPassword("newpassword123");

        mockMvc.perform(post("/admin/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("只有超级管理员可以创建管理员"));
    }

    @Test
    void testCreateAdminEndpointDuplicateUsername() throws Exception {
        String token = jwtService.issue(superAdminUser);

        CreateAdminRequest request = new CreateAdminRequest();
        request.setUsername("testuser"); // 已存在的用户名
        request.setPassword("newpassword123");

        mockMvc.perform(post("/admin/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("用户名已存在"));
    }

    @Test
    void testCreateAdminEndpointValidationError() throws Exception {
        String token = jwtService.issue(superAdminUser);

        // 测试用户名为空
        CreateAdminRequest request = new CreateAdminRequest();
        request.setUsername("");
        request.setPassword("password123");

        mockMvc.perform(post("/admin/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        // 测试密码太短
        request.setUsername("newuser");
        request.setPassword("123");

        mockMvc.perform(post("/admin/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testDisableAdminEndpointSuccess() throws Exception {
        String token = jwtService.issue(superAdminUser);

        DisableAdminRequest request = new DisableAdminRequest();
        request.setUserId(testUser.getId());

        mockMvc.perform(post("/admin/disable")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.message").value("管理员已禁用"));

        // 验证用户已禁用
        AdminUser disabledUser = userRepository.findById(testUser.getId()).orElseThrow();
        assertEquals(0, disabledUser.getStatus());
    }

    @Test
    void testDisableAdminEndpointUnauthorized() throws Exception {
        String token = jwtService.issue(testUser); // 普通管理员

        DisableAdminRequest request = new DisableAdminRequest();
        request.setUserId(firstTimeUser.getId());

        mockMvc.perform(post("/admin/disable")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("只有超级管理员可以禁用管理员"));
    }

    @Test
    void testDisableAdminEndpointCannotDisableSelf() throws Exception {
        String token = jwtService.issue(superAdminUser);

        DisableAdminRequest request = new DisableAdminRequest();
        request.setUserId(superAdminUser.getId());

        mockMvc.perform(post("/admin/disable")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("不能禁用自己"));
    }

    @Test
    void testDeleteAdminEndpointSuccess() throws Exception {
        String token = jwtService.issue(superAdminUser);

        DeleteAdminRequest request = new DeleteAdminRequest();
        request.setUserId(testUser.getId());

        mockMvc.perform(post("/admin/delete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.message").value("管理员已删除"));

        // 验证用户已删除
        assertFalse(userRepository.findById(testUser.getId()).isPresent());
    }

    @Test
    void testDeleteAdminEndpointUnauthorized() throws Exception {
        String token = jwtService.issue(testUser); // 普通管理员

        DeleteAdminRequest request = new DeleteAdminRequest();
        request.setUserId(firstTimeUser.getId());

        mockMvc.perform(post("/admin/delete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("只有超级管理员可以删除管理员"));
    }

    @Test
    void testDeleteAdminEndpointCannotDeleteSelf() throws Exception {
        String token = jwtService.issue(superAdminUser);

        DeleteAdminRequest request = new DeleteAdminRequest();
        request.setUserId(superAdminUser.getId());

        mockMvc.perform(post("/admin/delete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("不能删除自己"));
    }

    @Test
    void testDeleteAdminEndpointUserNotFound() throws Exception {
        String token = jwtService.issue(superAdminUser);

        DeleteAdminRequest request = new DeleteAdminRequest();
        request.setUserId(99999L); // 不存在的用户ID

        mockMvc.perform(post("/admin/delete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("用户不存在"));
    }
}