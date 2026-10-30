package org.congcong.proxyworker.router;

import org.congcong.common.dto.*;
import org.congcong.common.enums.*;
import org.congcong.proxyworker.ProxyWorkerTestFixtures;
import org.congcong.proxyworker.config.*;
import org.congcong.proxyworker.rules.RuleSetRegistry;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RouteSelectorTest {
    private final InboundConfig inbound = ProxyWorkerTestFixtures.inbound(ProtocolType.TP_PROXY);
    private final RouteSelector selector = new RouteSelector();

    private RouteRule rule(RouteConditionType type, MatchOp op, String value) {
        RouteRule rule = new RouteRule();
        rule.setConditionType(type);
        rule.setOp(op);
        rule.setValue(value);
        return rule;
    }

    private RouteConfig route(long id, RouteRule... rules) {
        RouteConfig route = ProxyWorkerTestFixtures.route(RoutePolicy.DIRECT, ProtocolType.NONE, null, null);
        route.setId(id);
        route.setRules(List.of(rules));
        return route;
    }

    private ProxyTunnelRequest request(String host) {
        return new ProxyTunnelRequest(ProtocolType.TP_PROXY, host, 443, inbound.getAnonymousUser(), inbound, null);
    }

    @Test void preservesFirstMatchingRouteRuleIndexAndFallback() {
        inbound.setConfigVersion("v1");
        RouteConfig first = route(1, rule(RouteConditionType.AD_BLOCK, MatchOp.IN, "*"),
                rule(RouteConditionType.DOMAIN, MatchOp.IN, "example.com"));
        RouteConfig second = route(2, rule(RouteConditionType.DOMAIN, MatchOp.IN, "*"));
        inbound.setRoutesMap(Map.of(0L, List.of(first, second)));
        ProxyTunnelRequest request = request("example.com");
        var selection = selector.select(request, inbound, 0L);
        assertSame(first, selection.route());
        assertFalse(selection.fallback());
        assertEquals("1:1", request.getTelemetryMatchedRuleId());
        assertNull(request.getTelemetryMatchedRuleSet());
        inbound.setRoutesMap(Map.of(0L, List.of(first)));
        assertSame(inbound.getDefaultRouteConfig(), selector.select(request("other.test"), inbound, 0L).route());
        assertTrue(selector.select(request("other.test"), inbound, 0L).fallback());
        inbound.setDefaultRouteConfig(null);
        assertNull(selector.select(request("other.test"), inbound, 0L).route());
    }

    @Test void geoNeedsRealIpAndUsesCachedCountryAndNotIn() {
        RouteConfig cn = route(1, rule(RouteConditionType.GEO, MatchOp.IN, "CN"));
        RouteConfig outside = route(2, rule(RouteConditionType.GEO, MatchOp.NOT_IN, "CN"));
        inbound.setRoutesMap(Map.of(0L, List.of(cn, outside)));
        assertTrue(selector.select(request("example.com"), inbound, 0L).fallback());
        ProxyTunnelRequest request = request("203.0.113.1");
        request.setLocationResolveSuccess(true);
        request.setCountry("NOT CN");
        assertSame(outside, selector.select(request, inbound, 0L).route());
        request = request("example.com");
        request.setTargetIp("203.0.113.1");
        request.setLocationResolveSuccess(true);
        request.setCountry("CN");
        assertSame(cn, selector.select(request, inbound, 0L).route());
    }

    @Test void ruleSetSetsTelemetryAndNeverMatchesLiteralEvenWithNotIn() {
        RuleSetItemDTO item = new RuleSetItemDTO();
        item.setType(RuleSetItemType.DOMAIN_SUFFIX);
        item.setValue("example.com");
        RuleSetDTO set = new RuleSetDTO();
        set.setRuleKey("udp-test");
        set.setMatchTarget(RuleSetMatchTarget.DOMAIN);
        set.setItems(List.of(item));
        RuleSetRegistry.refresh(List.of(set));
        try {
            RouteConfig route = route(1, rule(RouteConditionType.RULE_SET, MatchOp.IN, "udp-test"));
            inbound.setRoutesMap(Map.of(0L, List.of(route)));
            ProxyTunnelRequest request = request("a.example.com");
            assertSame(route, selector.select(request, inbound, 0L).route());
            assertEquals("udp-test", request.getTelemetryMatchedRuleSet());
            route.getRules().get(0).setOp(MatchOp.NOT_IN);
            assertTrue(selector.select(request("203.0.113.1"), inbound, 0L).fallback());
            assertSame(route, selector.select(request("other.test"), inbound, 0L).route());
        } finally {
            RuleSetRegistry.refresh(List.of());
        }
    }
}
