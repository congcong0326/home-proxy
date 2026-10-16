package org.congcong.controlmanager.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.congcong.common.dto.RouteDTO;
import org.congcong.common.dto.InboundRouteBinding;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.MatchOp;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RouteConditionType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.controlmanager.dto.route.CreateRouteRequest;
import org.congcong.controlmanager.entity.Route;
import org.congcong.controlmanager.entity.InboundConfig;
import org.congcong.controlmanager.repository.RuleSetRepository;
import org.congcong.controlmanager.repository.RouteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RouteServiceTest {

    private final RouteRepository routeRepository = mock(RouteRepository.class);
    private final RuleSetRepository ruleSetRepository = mock(RuleSetRepository.class);
    private final WorkerConfigDependencyResolver dependencyResolver = mock(WorkerConfigDependencyResolver.class);
    private final RouteService routeService = new RouteService(routeRepository, ruleSetRepository, dependencyResolver);

    @Test
    void rejectsVlessRealityRouteWhenRequiredConfigIsMissing() {
        when(routeRepository.existsByName("reality")).thenReturn(false);
        CreateRouteRequest request = vlessRealityRequest(Map.of(
                "publicKey", "j4VYpQ2F2P7N5L3S0M9K8J6H4G2F1D0C",
                "shortId", "6ba85179e30d4fc2",
                "uuid", "11111111-1111-1111-1111-111111111111"));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> routeService.createRoute(request));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals("VLESS REALITY serverName is required", error.getReason());
    }

    @Test
    void returnsOutboundProxyConfigForVlessRealityRoute() {
        when(routeRepository.existsByName("reality")).thenReturn(false);
        when(routeRepository.save(any(Route.class))).thenAnswer(invocation -> {
            Route route = invocation.getArgument(0);
            route.setId(99L);
            return route;
        });
        Map<String, Object> config = Map.of(
                "serverName", "www.example.com",
                "publicKey", "j4VYpQ2F2P7N5L3S0M9K8J6H4G2F1D0C",
                "shortId", "6ba85179e30d4fc2",
                "uuid", "11111111-1111-1111-1111-111111111111");

        RouteDTO route = routeService.createRoute(vlessRealityRequest(config));

        assertEquals(config, route.getOutboundProxyConfig());
    }

    @Test
    void rejectsAdBlockRouteCondition() {
        when(routeRepository.existsByName("ad-block")).thenReturn(false);
        CreateRouteRequest request = new CreateRouteRequest();
        request.setName("ad-block");
        request.setPolicy(RoutePolicy.DIRECT);
        request.setStatus(1);
        RouteRule rule = routeRule();
        rule.setConditionType(RouteConditionType.AD_BLOCK);
        request.setRules(List.of(rule));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> routeService.createRoute(request));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals("AD_BLOCK 路由条件已下线，请使用 RULE_SET 规则集替代", error.getReason());
    }

    @Test
    void refusesDeletingRouteReferencedByInboundConfig() {
        Route route = new Route();
        route.setId(7L);
        route.setName("直连规则");
        InboundConfig inbound = new InboundConfig();
        inbound.setId(12L);
        inbound.setName("家庭代理");
        inbound.setWorkerId("home-01");
        inbound.setListenIp("0.0.0.0");
        inbound.setPort(1080);
        inbound.setFallbackRouteId(7L);
        InboundRouteBinding binding = new InboundRouteBinding();
        binding.setRouteIds(List.of(7L));
        inbound.setInboundRouteBindings(Arrays.asList(null, binding, binding));
        InboundConfig disabled = new InboundConfig();
        disabled.setId(13L);
        disabled.setName("备用代理");
        disabled.setWorkerId("home-02");
        disabled.setListenIp("::");
        disabled.setPort(8388);
        disabled.setProtocol(ProtocolType.SHADOW_SOCKS);
        disabled.setStatus(0);
        disabled.setInboundRouteBindings(List.of(binding));
        when(routeRepository.findById(7L)).thenReturn(Optional.of(route));
        when(dependencyResolver.findInboundsReferencingRoute(7L)).thenReturn(List.of(inbound, disabled));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> routeService.deleteRoute(7L));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        String reason = error.getReason();
        assertTrue(reason.contains("路由“直连规则”（ID：7）"));
        assertTrue(reason.contains("家庭代理（ID：12，节点：home-01，监听：0.0.0.0:1080）：兜底路由、绑定组 #2 → 路由顺序、绑定组 #3 → 路由顺序"));
        assertTrue(reason.contains("备用代理（ID：13，节点：home-02，监听：[::]:8388，已禁用）：绑定关系 → 路由顺序"));
        assertTrue(reason.contains("请前往「入站配置」编辑以上配置"));
        assertTrue(reason.contains("保存后重试"));
        verify(routeRepository, never()).delete(eq(route));
    }

    @Test
    void deletesRouteAfterAllInboundReferencesAreRemoved() {
        Route route = new Route();
        route.setId(7L);
        when(routeRepository.findById(7L)).thenReturn(Optional.of(route));
        when(dependencyResolver.findInboundsReferencingRoute(7L)).thenReturn(List.of());

        routeService.deleteRoute(7L);

        verify(routeRepository).delete(route);
    }

    private CreateRouteRequest vlessRealityRequest(Map<String, Object> config) {
        CreateRouteRequest request = new CreateRouteRequest();
        request.setName("reality");
        request.setPolicy(RoutePolicy.OUTBOUND_PROXY);
        request.setOutboundTag("reality");
        request.setOutboundProxyType(ProtocolType.VLESS_REALITY);
        request.setOutboundProxyHost("reality.example.com");
        request.setOutboundProxyPort(443);
        request.setOutboundProxyConfig(config);
        request.setRules(List.of(routeRule()));
        request.setStatus(1);
        return request;
    }

    private RouteRule routeRule() {
        RouteRule rule = new RouteRule();
        rule.setConditionType(RouteConditionType.DOMAIN);
        rule.setOp(MatchOp.IN);
        rule.setValue("*");
        return rule;
    }
}
