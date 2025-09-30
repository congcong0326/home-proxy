package org.congcong.controlmanager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.congcong.controlmanager.dto.*;
import org.congcong.controlmanager.entity.AdminUser;
import org.congcong.controlmanager.entity.User;
import org.congcong.controlmanager.repository.AdminUserRepository;
import org.congcong.controlmanager.repository.UserRepository;
import org.congcong.controlmanager.security.JwtService;
import org.congcong.controlmanager.service.AdminAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;


    @Autowired
    private AdminAuthService authService;

    private User testUser1;
    private User testUser2;
    private User disabledUser;
    private String jwtToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        // 生成jwt
        adminUserRepository.deleteAll();
        AdminUser testUser;
        testUser = new AdminUser();
        testUser.setUsername("testuser");
        testUser.setPasswordHash(passwordEncoder.encode("password123"));
        testUser.setRoles("ADMIN");
        testUser.setMustChangePassword(false);
        testUser.setVer(1);
        testUser.setStatus(1);
        testUser.setCreatedAt(LocalDateTime.now());
        testUser.setUpdatedAt(LocalDateTime.now());
        adminUserRepository.save(testUser);
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("testuser");
        loginRequest.setPassword("password123");
        LoginResponse login = authService.login(loginRequest, "127.0.0.1", "");
        jwtToken = login.getToken();

        // 清理数据
        userRepository.deleteAll();

        // 创建测试数据
        testUser1 = new User();
        testUser1.setUsername("testuser1");
        testUser1.setCredential("credential1");
        testUser1.setStatus(1);
        testUser1.setRemark("Test user 1");
        testUser1.setCreatedAt(LocalDateTime.now());
        testUser1.setUpdatedAt(LocalDateTime.now());
        testUser1 = userRepository.save(testUser1);

        testUser2 = new User();
        testUser2.setUsername("testuser2");
        testUser2.setCredential("credential2");
        testUser2.setStatus(1);
        testUser2.setRemark("Test user 2");
        testUser2.setCreatedAt(LocalDateTime.now());
        testUser2.setUpdatedAt(LocalDateTime.now());
        testUser2 = userRepository.save(testUser2);

        disabledUser = new User();
        disabledUser.setUsername("disableduser");
        disabledUser.setCredential("credential3");
        disabledUser.setStatus(0);
        disabledUser.setRemark("Disabled user");
        disabledUser.setCreatedAt(LocalDateTime.now());
        disabledUser.setUpdatedAt(LocalDateTime.now());
        disabledUser = userRepository.save(disabledUser);
    }

    // 测试分页查询用户列表
    @Test
    void testGetUsersSuccess() throws Exception {
        mockMvc.perform(get("/api/users")
                        .header("Authorization", "Bearer " + jwtToken)
                        .param("page", "1")
                        .param("pageSize", "10")
                        .param("sortBy", "id")
                        .param("sortDir", "desc"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(10));
    }

    // 测试搜索用户
    @Test
    void testGetUsersWithSearch() throws Exception {
        mockMvc.perform(get("/api/users")
                        .header("Authorization", "Bearer " + jwtToken)
                        .param("page", "1")
                        .param("pageSize", "10")
                        .param("q", "testuser1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].username").value("testuser1"));
    }

    // 测试状态过滤
    @Test
    void testGetUsersWithStatusFilter() throws Exception {
        mockMvc.perform(get("/api/users")
                        .header("Authorization", "Bearer " + jwtToken)
                        .param("page", "1")
                        .param("pageSize", "10")
                        .param("status", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].status").value(0));
    }

    // 测试根据ID查询用户
    @Test
    void testGetUserByIdSuccess() throws Exception {
        mockMvc.perform(get("/api/users/{id}", testUser1.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(testUser1.getId()))
                .andExpect(jsonPath("$.username").value("testuser1"))
                .andExpect(jsonPath("$.status").value(1));
    }

    // 测试查询不存在的用户
    @Test
    void testGetUserByIdNotFound() throws Exception {
        mockMvc.perform(get("/api/users/{id}", 99999L)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNotFound());
    }

    // 测试创建用户成功
    @Test
    void testCreateUserSuccess() throws Exception {
        CreateUserRequest request = new CreateUserRequest();
        request.setUsername("newuser");
        request.setCredential("newcredential");
        request.setStatus(1);
        request.setRemark("New user");

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("newuser"))
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.remark").value("New user"));
    }

    // 测试创建用户 - 用户名已存在
    @Test
    void testCreateUserDuplicateUsername() throws Exception {
        CreateUserRequest request = new CreateUserRequest();
        request.setUsername("testuser1"); // 已存在的用户名
        request.setCredential("credential");
        request.setStatus(1);

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    // 测试创建用户 - 验证失败
    @Test
    void testCreateUserValidationError() throws Exception {
        CreateUserRequest request = new CreateUserRequest();
        request.setUsername(""); // 空用户名
        request.setCredential("credential");

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // 测试更新用户成功
    @Test
    void testUpdateUserSuccess() throws Exception {
        UpdateUserRequest request = new UpdateUserRequest();
        request.setUsername("updateduser");
        request.setCredential("updatedcredential");
        request.setStatus(0);
        request.setRemark("Updated user");

        mockMvc.perform(put("/api/users/{id}", testUser1.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("updateduser"))
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.remark").value("Updated user"));
    }

    // 测试更新不存在的用户
    @Test
    void testUpdateUserNotFound() throws Exception {
        UpdateUserRequest request = new UpdateUserRequest();
        request.setUsername("updateduser");
        request.setCredential("updatedcredential");
        request.setStatus(0);

        mockMvc.perform(put("/api/users/{id}", 99999L)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    // 测试删除用户成功
    @Test
    void testDeleteUserSuccess() throws Exception {
        mockMvc.perform(delete("/api/users/{id}", testUser1.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNoContent());
    }

    // 测试删除不存在的用户
    @Test
    void testDeleteUserNotFound() throws Exception {
        mockMvc.perform(delete("/api/users/{id}", 99999L)
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNotFound());
    }

    // 测试重置用户凭证成功
    @Test
    void testResetCredentialSuccess() throws Exception {
        UserController.ResetCredentialRequest request = new UserController.ResetCredentialRequest();
        request.setNewCredential("newcredential123");

        mockMvc.perform(put("/api/users/{id}/credential", testUser1.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    // 测试重置凭证 - 用户不存在
    @Test
    void testResetCredentialUserNotFound() throws Exception {
        UserController.ResetCredentialRequest request = new UserController.ResetCredentialRequest();
        request.setNewCredential("newcredential123");

        mockMvc.perform(put("/api/users/{id}/credential", 99999L)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    // 测试重置凭证 - 验证失败
    @Test
    void testResetCredentialValidationError() throws Exception {
        UserController.ResetCredentialRequest request = new UserController.ResetCredentialRequest();
        request.setNewCredential(""); // 空凭证

        mockMvc.perform(put("/api/users/{id}/credential", testUser1.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // 测试更新用户状态成功
    @Test
    void testUpdateStatusSuccess() throws Exception {
        UserController.UpdateStatusRequest request = new UserController.UpdateStatusRequest();
        request.setStatus(0);

        mockMvc.perform(put("/api/users/{id}/status", testUser1.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    // 测试更新状态 - 用户不存在
    @Test
    void testUpdateStatusUserNotFound() throws Exception {
        UserController.UpdateStatusRequest request = new UserController.UpdateStatusRequest();
        request.setStatus(0);

        mockMvc.perform(put("/api/users/{id}/status", 99999L)
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    // 测试更新状态 - 验证失败
    @Test
    void testUpdateStatusValidationError() throws Exception {
        UserController.UpdateStatusRequest request = new UserController.UpdateStatusRequest();
        request.setStatus(null); // 空状态

        mockMvc.perform(put("/api/users/{id}/status", testUser1.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // 测试未认证访问
    @Test
    void testUnauthorizedAccess() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isUnauthorized());
    }
}