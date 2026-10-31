package org.congcong.controlmanager.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.Optional;
import org.congcong.common.dto.InboundRouteBinding;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.MatchOp;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RouteConditionType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.controlmanager.dto.route.RouteRequest;
import org.congcong.controlmanager.dto.route.RouteView;
import org.congcong.controlmanager.entity.InboundConfig;
import org.congcong.controlmanager.entity.Outbound;
import org.congcong.controlmanager.entity.Route;
import org.congcong.controlmanager.enums.OutboundKind;
import org.congcong.controlmanager.enums.RouteAction;
import org.congcong.controlmanager.repository.OutboundRepository;
import org.congcong.controlmanager.repository.RewriteEntryRepository;
import org.congcong.controlmanager.repository.RewriteTableRepository;
import org.congcong.controlmanager.repository.RouteRepository;
import org.congcong.controlmanager.repository.RuleSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RouteServiceTest {

    private final RouteRepository routeRepository = mock(RouteRepository.class);
    private final RuleSetRepository ruleSetRepository = mock(RuleSetRepository.class);
    private final WorkerConfigDependencyResolver dependencyResolver = mock(WorkerConfigDependencyResolver.class);
    private final OutboundRepository outboundRepository = mock(OutboundRepository.class);
    private final RewriteTableRepository rewriteTableRepository = mock(RewriteTableRepository.class);
    private final RouteService routeService = new RouteService(routeRepository, ruleSetRepository, dependencyResolver,
            outboundRepository, rewriteTableRepository, mock(RewriteEntryRepository.class));

    @BeforeEach
    void setUp() {
        Outbound direct = outbound(1L, "直连", OutboundKind.DIRECT);
        direct.setBuiltinKey(OutboundService.BUILTIN_DIRECT);
        Outbound proxy = outbound(3L, "机场", OutboundKind.PROXY);
        when(outboundRepository.findByBuiltinKey(OutboundService.BUILTIN_DIRECT)).thenReturn(Optional.of(direct));
        when(outboundRepository.findById(1L)).thenReturn(Optional.of(direct));
        when(outboundRepository.findById(3L)).thenReturn(Optional.of(proxy));
        when(outboundRepository.findAllById(any())).thenReturn(List.of(direct, proxy));
        when(routeRepository.save(any(Route.class))).thenAnswer(invocation -> {
            Route route = invocation.getArgument(0);
            route.setId(99L);
            return route;
        });
    }

    @Test
    void forwardRouteDerivesPolicyFromOutboundKind() {
        RouteRequest request = request(RouteAction.FORWARD);
        request.setOutboundId(3L);

        RouteView route = routeService.createRoute(request);

        assertEquals(RoutePolicy.OUTBOUND_PROXY, route.getPolicy());
        assertEquals("机场", route.getOutboundName());
    }

    @Test
    void forwardRouteRequiresOutbound() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> routeService.createRoute(request(RouteAction.FORWARD)));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals("转发路由必须选择出站", error.getReason());
    }

    @Test
    void destinationRewritePinsBuiltinDirectOutbound() {
        RouteRequest request = request(RouteAction.REWRITE_DESTINATION);
        request.setOutboundId(3L);
        request.setRewriteTargetHost("192.168.10.85");
        request.setRewriteTargetPort(8096);

        RouteView route = routeService.createRoute(request);

        assertEquals(1L, route.getOutboundId());
        assertEquals(RoutePolicy.DESTINATION_OVERRIDE, route.getPolicy());
    }

    @Test
    void destinationRewriteRequiresPort() {
        RouteRequest request = request(RouteAction.REWRITE_DESTINATION);
        request.setRewriteTargetHost("192.168.10.85");

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> routeService.createRoute(request));

        assertEquals("改写端口必须在 1-65535 之间", error.getReason());
    }

    @Test
    void rewriteTableRouteRejectsExtraConditions() {
        RouteRequest request = request(RouteAction.REWRITE_DESTINATION);
        request.setRewriteTableId(5L);
        when(rewriteTableRepository.existsById(5L)).thenReturn(true);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> routeService.createRoute(request));

        assertEquals("引用改写表的路由不能再配置条件，表中的域名即匹配条件", error.getReason());
    }

    @Test
    void rewriteTableRouteStoresNoRulesOrFixedTarget() {
        RouteRequest request = request(RouteAction.REWRITE_DESTINATION);
        request.setRules(List.of());
        request.setRewriteTableId(5L);
        when(rewriteTableRepository.existsById(5L)).thenReturn(true);

        RouteView route = routeService.createRoute(request);

        assertEquals(5L, route.getRewriteTableId());
        assertTrue(route.getRules().isEmpty());
        assertNull(route.getRewriteTargetHost());
    }

    @Test
    void fallbackRouteCannotSwitchToRewriteTable() {
        Route existing = new Route();
        existing.setId(7L);
        when(routeRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(dependencyResolver.isFallbackRoute(7L)).thenReturn(true);
        RouteRequest request = request(RouteAction.REWRITE_DESTINATION);
        request.setRules(List.of());
        request.setRewriteTableId(5L);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> routeService.updateRoute(7L, request));

        assertEquals("该路由被用作入站的兜底路由，不能改为引用改写表", error.getReason());
    }

    @Test
    void dnsRewriteRequiresIpLiteral() {
        RouteRequest request = request(RouteAction.REWRITE_DNS);
        request.setRewriteTargetHost("nas.local");

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> routeService.createRoute(request));

        assertEquals("DNS 改写的应答必须是 IP 地址: nas.local", error.getReason());
    }

    @Test
    void rejectsAdBlockRouteCondition() {
        RouteRequest request = request(RouteAction.FORWARD);
        request.setOutboundId(1L);
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

    private RouteRequest request(RouteAction action) {
        RouteRequest request = new RouteRequest();
        request.setName("route");
        request.setAction(action);
        request.setRules(List.of(routeRule()));
        request.setStatus(1);
        return request;
    }

    private static Outbound outbound(Long id, String name, OutboundKind kind) {
        Outbound outbound = new Outbound();
        outbound.setId(id);
        outbound.setName(name);
        outbound.setKind(kind);
        return outbound;
    }

    private RouteRule routeRule() {
        RouteRule rule = new RouteRule();
        rule.setConditionType(RouteConditionType.DOMAIN);
        rule.setOp(MatchOp.IN);
        rule.setValue("*");
        return rule;
    }
}
