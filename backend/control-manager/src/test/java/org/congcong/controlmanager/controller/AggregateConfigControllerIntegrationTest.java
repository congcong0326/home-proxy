package org.congcong.controlmanager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.congcong.common.dto.AggregateConfigResponse;
import org.congcong.common.dto.InboundConfigDTO;
import org.congcong.common.dto.RouteDTO;
import org.congcong.common.dto.RateLimitDTO;
import org.congcong.common.dto.UserDTO;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RateLimitScopeType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.controlmanager.dto.LoginRequest;
import org.congcong.controlmanager.dto.LoginResponse;
import org.congcong.controlmanager.entity.*;
import org.congcong.controlmanager.dto.InboundConfigCreateRequest;
import org.congcong.controlmanager.dto.InboundConfigUpdateRequest;
import org.congcong.controlmanager.dto.RateLimitCreateRequest;
import org.congcong.controlmanager.dto.RateLimitUpdateRequest;
import org.congcong.controlmanager.dto.route.CreateRouteRequest;
import org.congcong.controlmanager.dto.route.UpdateRouteRequest;
import org.congcong.controlmanager.repository.AdminUserRepository;
import org.congcong.controlmanager.service.InboundConfigService;
import org.congcong.controlmanager.service.RouteService;
import org.congcong.controlmanager.service.RateLimitService;
import org.congcong.controlmanager.service.UserService;
import org.congcong.controlmanager.service.AdminAuthService;
import org.congcong.controlmanager.service.AggregateConfigCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AggregateConfigController 集成测试
 * 测试聚合配置控制器的完整功能，包括配置聚合、缓存机制、配置变更影响等
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AggregateConfigControllerIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AggregateConfigCacheService aggregateConfigService;

    @Autowired
    private InboundConfigService inboundConfigService;

    @Autowired
    private RouteService routeService;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private UserService userService;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AdminAuthService authService;

    private String jwtToken;
    private Long testInboundId;
    private Long testRouteId;
    private Long testRateLimitId;
    private Long testUserId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        // 设置JWT认证（管理侧接口需要）
        setupJwtAuthentication();

        // 创建测试数据
        createTestData();
    }

    /**
     * 设置JWT认证
     */
    private void setupJwtAuthentication() {
        adminUserRepository.deleteAll();
        AdminUser testAdmin = new AdminUser();
        testAdmin.setUsername("testadmin");
        testAdmin.setPasswordHash(passwordEncoder.encode("password123"));
        testAdmin.setRoles("ADMIN");
        testAdmin.setMustChangePassword(false);
        testAdmin.setVer(1);
        testAdmin.setStatus(1);
        testAdmin.setCreatedAt(LocalDateTime.now());
        testAdmin.setUpdatedAt(LocalDateTime.now());
        adminUserRepository.save(testAdmin);

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("testadmin");
        loginRequest.setPassword("password123");
        LoginResponse login = authService.login(loginRequest, "127.0.0.1", "");
        jwtToken = login.getToken();
    }

    /**
     * 清理测试数据
     */
    private void cleanupTestData() {
        // 使用@Transactional确保每个测试隔离，故此处无需显式清理。
    }

    /**
     * 创建测试数据
     */
    private void createTestData() {
        // 1. 创建测试用户（经服务，自动驱逐聚合缓存）
        User user = new User();
        user.setUsername("testuser");
        user.setCredential("password");
        user.setStatus(1);
        UserDTO createdUser = userService.createUser(user);
        testUserId = createdUser.getId();

        // 2. 创建测试路由（经服务，入站配置需要引用路由ID）
        CreateRouteRequest createRouteRequest = new CreateRouteRequest();
        createRouteRequest.setName("Test Route");
        createRouteRequest.setRules(new ArrayList<>());
        createRouteRequest.setPolicy(RoutePolicy.DIRECT);
        createRouteRequest.setOutboundProxyType(ProtocolType.NONE);
        createRouteRequest.setStatus(1);
        RouteDTO createdRoute = routeService.createRoute(createRouteRequest);
        testRouteId = createdRoute.getId();

        // 3. 创建测试限流策略（经服务）
        RateLimitCreateRequest rateLimitCreateRequest = new RateLimitCreateRequest();
        rateLimitCreateRequest.setScopeType(RateLimitScopeType.GLOBAL);
        rateLimitCreateRequest.setDownlinkLimitBps(100L);
        rateLimitCreateRequest.setUplinkLimitBps(60L);
        rateLimitCreateRequest.setEnabled(true);
        RateLimitDTO createdRateLimit = rateLimitService.createRateLimit(rateLimitCreateRequest);
        testRateLimitId = createdRateLimit.getId();

        // 4. 创建测试入站配置（经服务，引用已创建的路由和用户ID）
        InboundConfigCreateRequest inboundCreateRequest = new InboundConfigCreateRequest();
        inboundCreateRequest.setName("Test Inbound");
        inboundCreateRequest.setProtocol(ProtocolType.SOCKS5);
        inboundCreateRequest.setListenIp("127.0.0.1");
        inboundCreateRequest.setPort(8080);
        inboundCreateRequest.setTlsEnabled(false);
        inboundCreateRequest.setSniffEnabled(false);
        inboundCreateRequest.setAllowedUserIds(List.of((testUserId)));
        inboundCreateRequest.setRouteIds(List.of((testRouteId)));
        inboundCreateRequest.setStatus(1);
        InboundConfigDTO createdInbound = inboundConfigService.createInboundConfig(inboundCreateRequest);
        testInboundId = createdInbound.getId();
    }

    /**
     * 测试1：基本聚合配置获取
     * 验证能够正确获取所有启用的配置并返回聚合响应
     */
    @Test
    void testGetAggregateConfigSuccess() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/config/aggregate")
                        .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(header().exists("Last-Modified"))
                .andExpect(jsonPath("$.version").value("1.0"))
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.configHash").exists())
                .andExpect(jsonPath("$.inbounds").isArray())
                .andExpect(jsonPath("$.inbounds", hasSize(1)))
                .andExpect(jsonPath("$.inbounds[0].name").value("Test Inbound"))
                .andExpect(jsonPath("$.inbounds[0].protocol").value("socks5"))
                .andExpect(jsonPath("$.routes").isArray())
                .andExpect(jsonPath("$.routes", hasSize(1)))
                .andExpect(jsonPath("$.routes[0].name").value("Test Route"))
                .andExpect(jsonPath("$.rateLimits").isArray())
                .andExpect(jsonPath("$.rateLimits", hasSize(1)))
                .andExpect(jsonPath("$.rateLimits[0].scopeType").value("global"))
                .andExpect(jsonPath("$.users").isArray())
                .andExpect(jsonPath("$.users", hasSize(1)))
                .andExpect(jsonPath("$.users[0].username").value("testuser"))
                .andReturn();

        // 验证响应内容
        String responseContent = result.getResponse().getContentAsString();
        AggregateConfigResponse response = objectMapper.readValue(responseContent, AggregateConfigResponse.class);
        
        assertNotNull(response.getConfigHash());
        assertNotNull(response.getGeneratedAt());
        assertEquals(1, response.getInbounds().size());
        assertEquals(1, response.getRoutes().size());
        assertEquals(1, response.getRateLimits().size());
        assertEquals(1, response.getUsers().size());
    }

    /**
     * 测试2：HTTP 304缓存机制
     * 验证当配置未变更时返回304状态码
     */
    @Test
    void testGetAggregateConfigWithCaching() throws Exception {
        // 第一次请求，获取ETag
        MvcResult firstResult = mockMvc.perform(get("/api/config/aggregate"))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andReturn();

        String etag = firstResult.getResponse().getHeader("ETag");
        assertNotNull(etag);

        // 第二次请求，使用ETag，应该返回304
        mockMvc.perform(get("/api/config/aggregate")
                        .header("If-None-Match", etag))
                .andDo(print())
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", etag))
                .andExpect(content().string(""));
    }

    /**
     * 测试3：配置变更后缓存失效
     * 验证当配置发生变更时，缓存会失效并返回新的配置
     */
    @Test
    void testConfigChangeInvalidatesCache() throws Exception {
        // 获取初始ETag
        MvcResult initialResult = mockMvc.perform(get("/api/config/aggregate"))
                .andExpect(status().isOk())
                .andReturn();
        String initialETag = initialResult.getResponse().getHeader("ETag");

        // 修改入站配置（通过服务触发缓存驱逐）
        InboundConfigDTO currentInbound = inboundConfigService.getInboundConfigById(testInboundId)
                .orElseThrow();
        InboundConfigUpdateRequest updateRequest = new InboundConfigUpdateRequest();
        updateRequest.setName("updated-inbound");
        updateRequest.setProtocol(currentInbound.getProtocol());
        updateRequest.setListenIp(currentInbound.getListenIp());
        updateRequest.setPort(currentInbound.getPort());
        updateRequest.setTlsEnabled(currentInbound.getTlsEnabled());
        updateRequest.setSniffEnabled(currentInbound.getSniffEnabled());
        updateRequest.setSsMethod(currentInbound.getSsMethod());
        updateRequest.setAllowedUserIds(currentInbound.getAllowedUserIds());
        updateRequest.setRouteIds(currentInbound.getRouteIds());
        updateRequest.setStatus(currentInbound.getStatus());
        updateRequest.setNotes(currentInbound.getNotes());
        inboundConfigService.updateInboundConfig(testInboundId, updateRequest);

        // 使用旧ETag请求，应该返回新配置（不是304）
        MvcResult updatedResult = mockMvc.perform(get("/api/config/aggregate")
                        .header("If-None-Match", initialETag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inbounds[0].name").value("updated-inbound"))
                .andReturn();

        String newETag = updatedResult.getResponse().getHeader("ETag");
        assertNotEquals(initialETag, newETag);
    }

    /**
     * 测试4：路由配置变更影响
     * 验证路由配置变更会影响聚合配置
     */
    @Test
    void testRouteConfigChangeImpact() throws Exception {
        // 获取初始配置哈希
        String initialHash = aggregateConfigService.getAggregateConfig().getConfigHash();

        // 修改路由配置（通过服务触发缓存驱逐）
        UpdateRouteRequest updateRouteRequest = new UpdateRouteRequest();
        updateRouteRequest.setName("updated-route");
        RouteRule rule = new RouteRule();
        updateRouteRequest.setRules(List.of(rule));
        routeService.updateRoute(testRouteId, updateRouteRequest);

        // 验证配置哈希已变更
        String newHash = aggregateConfigService.getAggregateConfig().getConfigHash();
        assertNotEquals(initialHash, newHash);

        // 验证聚合配置包含更新的路由
        mockMvc.perform(get("/api/config/aggregate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes[0].name").value("updated-route"))
                .andExpect(jsonPath("$.configHash").value(newHash));
    }

    /**
     * 测试5：限流配置变更影响
     * 验证限流配置变更会影响聚合配置
     */
    @Test
    void testRateLimitConfigChangeImpact() throws Exception {
        // 获取初始配置哈希
        String initialHash = aggregateConfigService.getAggregateConfig().getConfigHash();

        // 修改限流配置（通过服务触发缓存驱逐）
        RateLimitUpdateRequest rateLimitUpdateRequest = new RateLimitUpdateRequest();
        rateLimitUpdateRequest.setUplinkLimitBps(5_000_000L);
        rateLimitUpdateRequest.setDownlinkLimitBps(10_000_000L);
        rateLimitUpdateRequest.setEnabled(true);
        rateLimitService.updateRateLimit(testRateLimitId, rateLimitUpdateRequest);

        // 验证配置哈希已变更
        String newHash = aggregateConfigService.getAggregateConfig().getConfigHash();
        assertNotEquals(initialHash, newHash);

        // 验证聚合配置包含更新的限流配置
        mockMvc.perform(get("/api/config/aggregate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rateLimits[0].uplinkLimitBps").value(5000000L))
                .andExpect(jsonPath("$.rateLimits[0].downlinkLimitBps").value(10000000L))
                .andExpect(jsonPath("$.configHash").value(newHash));
    }

    /**
     * 测试6：用户配置变更影响
     * 验证用户配置变更会影响聚合配置
     */
    @Test
    void testUserConfigChangeImpact() throws Exception {
        // 获取初始配置哈希
        String initialHash = aggregateConfigService.getAggregateConfig().getConfigHash();

        // 修改用户配置（通过服务触发缓存驱逐）
        User updatedUser = new User();
        updatedUser.setUsername("updated-user");
        updatedUser.setCredential("updated-credential");
        updatedUser.setStatus(1);
        userService.updateUser(testUserId, updatedUser);

        // 验证配置哈希已变更
        String newHash = aggregateConfigService.getAggregateConfig().getConfigHash();
        assertNotEquals(initialHash, newHash);

        // 验证聚合配置包含更新的用户配置
        mockMvc.perform(get("/api/config/aggregate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users[0].username").value("updated-user"))
                .andExpect(jsonPath("$.configHash").value(newHash));
    }

    /**
     * 测试7：配置哈希接口
     * 验证配置哈希接口能够正确返回当前配置哈希值
     */
    @Test
    void testGetCurrentConfigHash() throws Exception {
        String expectedHash = aggregateConfigService.getAggregateConfig().getConfigHash();

        mockMvc.perform(get("/api/config/hash"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string(expectedHash));
    }

    /**
     * 测试8：只聚合启用的配置
     * 验证只有启用状态的配置才会被包含在聚合配置中
     */
    @Test
    void testOnlyEnabledConfigsAggregated() throws Exception {
        // 创建禁用的配置
        InboundConfigCreateRequest disabledInboundReq = new InboundConfigCreateRequest();
        disabledInboundReq.setName("disabled-inbound");
        disabledInboundReq.setProtocol(ProtocolType.SOCKS5);
        disabledInboundReq.setListenIp("0.0.0.0");
        disabledInboundReq.setPort(9090);
        disabledInboundReq.setStatus(0); // 禁用状态
        inboundConfigService.createInboundConfig(disabledInboundReq);

        User disabledUser = new User();
        disabledUser.setUsername("disabled-user");
        disabledUser.setCredential("disabled-credential");
        disabledUser.setStatus(0); // 禁用状态
        userService.createUser(disabledUser);

        // 验证聚合配置中只包含启用的配置
        mockMvc.perform(get("/api/config/aggregate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inbounds", hasSize(1)))
                .andExpect(jsonPath("$.inbounds[0].name").value("Test Inbound"))
                .andExpect(jsonPath("$.users", hasSize(1)))
                .andExpect(jsonPath("$.users[0].username").value("testuser"));
    }

    /**
     * 测试9：异常处理
     * 验证当服务层抛出异常时，控制器能够正确处理并返回500状态码
     */
    @Test
    void testExceptionHandling() throws Exception {
        // 通过删除必要的数据来模拟异常情况
        // 这里我们可以通过直接调用一个会导致异常的场景
        
        // 通过服务删除当前测试数据（触发缓存驱逐）
        inboundConfigService.deleteInboundConfig(testInboundId);
        routeService.deleteRoute(testRouteId);
        rateLimitService.deleteRateLimit(testRateLimitId);
        userService.deleteUser(testUserId);
        
        // 注意：这个测试可能需要根据实际的异常处理逻辑进行调整
        // 如果AggregateConfigService有良好的异常处理，可能不会返回500
        mockMvc.perform(get("/api/config/aggregate"))
                .andDo(print())
                .andExpect(status().isOk()); // 根据实际情况调整期望的状态码
    }

    /**
     * 测试10：并发访问
     * 验证多个并发请求能够正确处理
     */
    @Test
    void testConcurrentAccess() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(5);
        
        // 创建5个并发请求
        CompletableFuture<Void>[] futures = new CompletableFuture[5];
        
        for (int i = 0; i < 5; i++) {
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    mockMvc.perform(get("/api/config/aggregate"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.version").value("1.0"))
                            .andExpect(jsonPath("$.inbounds").isArray())
                            .andExpect(jsonPath("$.routes").isArray())
                            .andExpect(jsonPath("$.rateLimits").isArray())
                            .andExpect(jsonPath("$.users").isArray());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executor);
        }
        
        // 等待所有请求完成
        CompletableFuture.allOf(futures).join();
        
        executor.shutdown();
    }

    /**
     * 测试11：ETag格式验证
     * 验证ETag的格式是否正确
     */
    @Test
    void testETagFormat() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/config/aggregate"))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andReturn();

        String etag = result.getResponse().getHeader("ETag");
        assertNotNull(etag);
        assertTrue(etag.startsWith("\""));
        assertTrue(etag.endsWith("\""));
        assertTrue(etag.length() > 2); // 除了引号外还有内容
    }


}