package org.congcong.controlmanager.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.congcong.common.dto.RouteDTO;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.MatchOp;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RouteConditionType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.controlmanager.dto.route.CreateRouteRequest;
import org.congcong.controlmanager.entity.Route;
import org.congcong.controlmanager.repository.RuleSetRepository;
import org.congcong.controlmanager.repository.RouteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RouteServiceTest {

    private final RouteRepository routeRepository = mock(RouteRepository.class);
    private final RuleSetRepository ruleSetRepository = mock(RuleSetRepository.class);
    private final RouteService routeService = new RouteService(routeRepository, ruleSetRepository);

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
