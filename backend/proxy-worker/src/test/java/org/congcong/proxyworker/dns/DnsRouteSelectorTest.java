package org.congcong.proxyworker.dns;

import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.MatchOp;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RouteConditionType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;

class DnsRouteSelectorTest {
    @Test
    void usesBoundDeviceRulesThenAnonymousRulesThenFallback() {
        UserConfig device = user(1L);
        UserConfig anonymous = user(2L);
        RouteConfig deviceRule = route("device.example");
        RouteConfig anonymousRule = route("anon.example");
        RouteConfig fallback = new RouteConfig();
        fallback.setPolicy(RoutePolicy.OUTBOUND_PROXY);
        InboundConfig dns = new InboundConfig();
        dns.setProtocol(ProtocolType.DNS_SERVER);
        dns.setAnonymousUser(anonymous);
        dns.setDeviceIpMapUser(Map.of("192.0.2.10", device));
        dns.setRoutesMap(Map.of(device.getId(), List.of(deviceRule), anonymous.getId(), List.of(anonymousRule)));
        dns.setDefaultRouteConfig(fallback);

        assertSame(deviceRule, DnsRouteSelector.select(dns, "192.0.2.10", "DEVICE.EXAMPLE.").route());
        assertSame(anonymousRule, DnsRouteSelector.select(dns, "192.0.2.20", "anon.example.").route());
        assertSame(fallback, DnsRouteSelector.select(dns, "192.0.2.20", "other.example.").route());
    }

    private UserConfig user(Long id) {
        UserConfig user = new UserConfig();
        user.setId(id);
        return user;
    }

    private RouteConfig route(String domain) {
        RouteRule rule = new RouteRule();
        rule.setConditionType(RouteConditionType.DOMAIN);
        rule.setOp(MatchOp.IN);
        rule.setValue(domain);
        RouteConfig route = new RouteConfig();
        route.setRules(List.of(rule));
        return route;
    }
}
