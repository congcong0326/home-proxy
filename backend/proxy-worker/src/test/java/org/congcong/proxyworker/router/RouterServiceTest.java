package org.congcong.proxyworker.router;

import io.netty.channel.embedded.EmbeddedChannel;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.MatchOp;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RouteConditionType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouterServiceTest {

    @Test
    void attachesDnsSnapshotCapturedOnTheChannel() {
        UserConfig user = user();
        InboundConfig inbound = inbound(user, List.of(), route(9L, "fallback", RoutePolicy.BLOCK, domainRule("*")));
        inbound.setConfigVersion("accepted-v1");
        InboundConfig dns = new InboundConfig();
        dns.setConfigVersion("accepted-v1");
        EmbeddedChannel channel = new EmbeddedChannel(RouterService.getInstance());
        try {
            ChannelAttributes.setProxyContext(channel, new ProxyContext());
            channel.attr(ChannelAttributes.DNS_INBOUND_CONFIG).set(dns);
            ProxyTunnelRequest request = new ProxyTunnelRequest(ProtocolType.SOCKS5, "example.com", 443,
                    user, inbound, null);
            channel.writeInbound(request);
            ProxyTunnelRequest routed = channel.readInbound();
            assertSame(dns, routed.getDnsInboundConfig());
            assertTrue(routed.isDnsInboundConfigCaptured());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void recordsAbsenceOfDnsInAcceptedSnapshot() {
        UserConfig user = user();
        InboundConfig inbound = inbound(user, List.of(), route(9L, "fallback", RoutePolicy.BLOCK, domainRule("*")));
        EmbeddedChannel channel = new EmbeddedChannel(RouterService.getInstance());
        try {
            ChannelAttributes.setProxyContext(channel, new ProxyContext());
            ProxyTunnelRequest request = new ProxyTunnelRequest(ProtocolType.SOCKS5, "example.com", 443,
                    user, inbound, null);
            channel.writeInbound(request);
            ProxyTunnelRequest routed = channel.readInbound();
            assertNull(routed.getDnsInboundConfig());
            assertTrue(routed.isDnsInboundConfigCaptured());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void geoRouteDoesNotMatchDomainTargets() {
        UserConfig user = user();
        RouteConfig geoCn = route(1L, "geo-cn-direct", RoutePolicy.DIRECT, geoRule("CN"));
        RouteConfig fallback = route(2L, "fallback-block", RoutePolicy.BLOCK, domainRule("*"));
        InboundConfig inbound = inbound(user, List.of(geoCn), fallback);
        ProxyTunnelRequest request = new ProxyTunnelRequest(
                ProtocolType.SOCKS5,
                "example.com",
                443,
                user,
                inbound,
                null);

        EmbeddedChannel channel = new EmbeddedChannel(RouterService.getInstance());
        ChannelAttributes.setProxyContext(channel, new ProxyContext());
        channel.writeInbound(request);

        ProxyTunnelRequest routed = channel.readInbound();
        assertSame(fallback, routed.getRouteConfig());
        channel.finishAndReleaseAll();
    }

    @Test
    void geoRouteMatchesIpTargetsUsingGeoIpOnly() {
        UserConfig user = user();
        RouteConfig privateIp = route(1L, "private-direct", RoutePolicy.DIRECT, geoRule("CN", MatchOp.NOT_IN));
        RouteConfig fallback = route(2L, "fallback-block", RoutePolicy.BLOCK, domainRule("*"));
        InboundConfig inbound = inbound(user, List.of(privateIp), fallback);
        ProxyTunnelRequest request = new ProxyTunnelRequest(
                ProtocolType.SOCKS5,
                "192.168.1.85",
                3000,
                user,
                inbound,
                null);

        EmbeddedChannel channel = new EmbeddedChannel(RouterService.getInstance());
        ChannelAttributes.setProxyContext(channel, new ProxyContext());
        channel.writeInbound(request);

        ProxyTunnelRequest routed = channel.readInbound();
        assertSame(privateIp, routed.getRouteConfig());
        assertNotNull(routed.getDestinationAddressBlocked());
        channel.finishAndReleaseAll();
    }

    @Test
    void destinationOverrideTrustsConfiguredInternalTarget() {
        UserConfig user = user();
        RouteConfig override = route(1L, "trusted-override", RoutePolicy.DESTINATION_OVERRIDE, domainRule("*"));
        override.setOutboundProxyHost("192.168.10.145");
        override.setOutboundProxyPort(22);
        InboundConfig inbound = inbound(user, List.of(), override);
        ProxyTunnelRequest request = new ProxyTunnelRequest(
                ProtocolType.SOCKS5, "192.168.10.145", 22, user, inbound, null);

        EmbeddedChannel channel = new EmbeddedChannel(RouterService.getInstance());
        ChannelAttributes.setProxyContext(channel, new ProxyContext());
        channel.writeInbound(request);

        ProxyTunnelRequest routed = channel.readInbound();
        assertSame(override, routed.getRouteConfig());
        assertNull(routed.getDestinationAddressBlocked());
        channel.finishAndReleaseAll();
    }

    @Test
    void tlsSniUsesConfiguredAnonymousUserForRouteBinding() {
        UserConfig authenticatedLikeUser = user();
        UserConfig anonymous = user();
        anonymous.setId(200L);
        anonymous.setUsername("anonymous");
        RouteConfig sniRoute = route(3L, "sni-direct", RoutePolicy.DIRECT, domainRule("example.com"));
        RouteConfig fallback = route(4L, "fallback-block", RoutePolicy.BLOCK, domainRule("*"));
        InboundConfig inbound = inbound(anonymous, List.of(sniRoute), fallback);
        inbound.setProtocol(ProtocolType.TLS_SNI_TCP);
        inbound.setAnonymousUser(anonymous);
        ProxyTunnelRequest request = new ProxyTunnelRequest(
                ProtocolType.TLS_SNI_TCP, "example.com", 443, authenticatedLikeUser, inbound, null);

        EmbeddedChannel channel = new EmbeddedChannel(RouterService.getInstance());
        ChannelAttributes.setProxyContext(channel, new ProxyContext());
        channel.writeInbound(request);

        ProxyTunnelRequest routed = channel.readInbound();
        assertSame(sniRoute, routed.getRouteConfig());
        channel.finishAndReleaseAll();
    }

    private InboundConfig inbound(UserConfig user, List<RouteConfig> routes, RouteConfig fallback) {
        InboundConfig inbound = new InboundConfig();
        inbound.setProtocol(ProtocolType.SOCKS5);
        inbound.setRoutesMap(Map.of(user.getId(), routes));
        inbound.setDefaultRouteConfig(fallback);
        return inbound;
    }

    private UserConfig user() {
        UserConfig user = new UserConfig();
        user.setId(100L);
        user.setUsername("tester");
        return user;
    }

    private RouteConfig route(Long id, String name, RoutePolicy policy, RouteRule rule) {
        RouteConfig route = new RouteConfig();
        route.setId(id);
        route.setName(name);
        route.setPolicy(policy);
        route.setRules(List.of(rule));
        return route;
    }

    private RouteRule geoRule(String country) {
        return geoRule(country, MatchOp.IN);
    }

    private RouteRule geoRule(String country, MatchOp op) {
        RouteRule rule = new RouteRule();
        rule.setConditionType(RouteConditionType.GEO);
        rule.setOp(op);
        rule.setValue(country);
        return rule;
    }

    private RouteRule domainRule(String domain) {
        RouteRule rule = new RouteRule();
        rule.setConditionType(RouteConditionType.DOMAIN);
        rule.setOp(MatchOp.IN);
        rule.setValue(domain);
        return rule;
    }
}
