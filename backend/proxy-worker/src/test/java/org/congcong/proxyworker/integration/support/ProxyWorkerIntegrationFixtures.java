package org.congcong.proxyworker.integration.support;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.DomainRuleType;
import org.congcong.common.enums.MatchOp;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.ProxyEncAlgo;
import org.congcong.common.enums.RouteConditionType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.common.util.geo.DomainFakeLoader;
import org.congcong.common.util.geo.DomainRuleEngine;
import org.congcong.common.util.geo.DomainRuleSet;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.config.UserConfig;

public final class ProxyWorkerIntegrationFixtures {
    public static final String USERNAME = "it-user";
    public static final String PASSWORD = "it-pass";

    private ProxyWorkerIntegrationFixtures() {
    }

    public static UserConfig user() {
        UserConfig user = new UserConfig();
        user.setId(1001L);
        user.setUsername(USERNAME);
        user.setCredential(PASSWORD);
        user.setIpAddress("127.0.0.1");
        return user;
    }

    public static InboundConfig socksInbound(int port, List<RouteConfig> routes, RouteConfig defaultRoute) {
        return tcpInbound(2001L, ProtocolType.SOCKS5, port, routes, defaultRoute);
    }

    public static InboundConfig httpInbound(int port, List<RouteConfig> routes, RouteConfig defaultRoute) {
        return tcpInbound(2002L, ProtocolType.HTTPS_CONNECT, port, routes, defaultRoute);
    }

    public static InboundConfig dnsInbound(int port, List<RouteConfig> routes, RouteConfig defaultRoute) {
        UserConfig user = user();
        InboundConfig inbound = baseInbound(2003L, ProtocolType.DNS_SERVER, port, defaultRoute);
        inbound.setUsersMap(new HashMap<>());
        inbound.setDeviceIpMapUser(Map.of("127.0.0.1", user));
        inbound.setRoutesMap(Map.of(user.getId(), routes));
        return inbound;
    }

    public static RouteConfig directRoute() {
        return route(3001L, "direct", RoutePolicy.DIRECT, ProtocolType.NONE, null, null);
    }

    public static RouteConfig blockRoute(String domain) {
        RouteConfig route = route(3002L, "block", RoutePolicy.BLOCK, ProtocolType.NONE, null, null);
        route.setRules(List.of(domainRule(domain)));
        return route;
    }

    public static RouteConfig dnsRewriteRoute(String answerIp) {
        return route(3003L, "dns-rewrite", RoutePolicy.DNS_REWRITING, ProtocolType.NONE, answerIp, 53);
    }

    public static RouteConfig outboundRoute(ProtocolType outboundType, String host, int port) {
        return route(3004L, "outbound-" + outboundType.name().toLowerCase(), RoutePolicy.OUTBOUND_PROXY, outboundType, host, port);
    }

    public static RouteConfig outboundRouteForDomain(String domain, ProtocolType outboundType, String host, int port) {
        RouteConfig route = outboundRoute(outboundType, host, port);
        route.setRules(List.of(domainRule(domain)));
        return route;
    }

    public static RouteConfig socksOutboundRoute(String host, int port) {
        return outboundRoute(ProtocolType.SOCKS5, host, port);
    }

    public static RouteConfig httpOutboundRoute(String host, int port) {
        return outboundRoute(ProtocolType.HTTPS_CONNECT, host, port);
    }

    public static RouteConfig shadowsocksOutboundRoute(String host, int port, ProxyEncAlgo method, String password) {
        RouteConfig route = outboundRoute(ProtocolType.SHADOW_SOCKS, host, port);
        route.setOutboundProxyEncAlgo(method);
        route.setOutboundProxyPassword(password);
        return route;
    }

    public static RouteConfig vlessRealityOutboundRoute(String host,
                                                        int port,
                                                        String serverName,
                                                        String publicKey,
                                                        String shortId,
                                                        String uuid) {
        RouteConfig route = outboundRoute(ProtocolType.VLESS_REALITY, host, port);
        route.setOutboundProxyConfig(Map.of(
                "serverName", serverName,
                "publicKey", publicKey,
                "shortId", shortId,
                "uuid", uuid,
                "flow", "xtls-rprx-vision",
                "connectTimeoutMillis", 10000));
        return route;
    }

    public static RouteConfig dnsForwardRoute(String host, int port) {
        return outboundRoute(ProtocolType.DNS_SERVER, host, port);
    }

    public static void installDomainRulesForTests() {
        try {
            Field rulesField = DomainRuleEngine.class.getDeclaredField("RULES");
            rulesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            ConcurrentMap<DomainRuleType, DomainRuleSet> rules =
                    (ConcurrentMap<DomainRuleType, DomainRuleSet>) rulesField.get(null);
            rules.put(DomainRuleType.DOMAIN, new DomainFakeLoader().load());

            Field initField = DomainRuleEngine.class.getDeclaredField("init");
            initField.setAccessible(true);
            initField.setBoolean(null, true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to install test domain rules", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load test domain rules", e);
        }
    }

    private static InboundConfig tcpInbound(Long id, ProtocolType protocol, int port, List<RouteConfig> routes, RouteConfig defaultRoute) {
        UserConfig user = user();
        InboundConfig inbound = baseInbound(id, protocol, port, defaultRoute);
        inbound.setUsersMap(Map.of(user.getUsername(), user));
        inbound.setDeviceIpMapUser(new HashMap<>());
        inbound.setRoutesMap(Map.of(user.getId(), routes));
        return inbound;
    }

    private static InboundConfig baseInbound(Long id, ProtocolType protocol, int port, RouteConfig defaultRoute) {
        InboundConfig inbound = new InboundConfig();
        inbound.setId(id);
        inbound.setName(protocol.name().toLowerCase() + "-it");
        inbound.setProtocol(protocol);
        inbound.setListenIp("127.0.0.1");
        inbound.setPort(port);
        inbound.setTlsEnabled(false);
        inbound.setSniffEnabled(true);
        inbound.setUsersMap(new HashMap<>());
        inbound.setDeviceIpMapUser(new HashMap<>());
        inbound.setRoutesMap(new HashMap<>());
        inbound.setAnonymousUser(user());
        inbound.setDefaultRouteConfig(defaultRoute);
        return inbound;
    }

    private static RouteConfig route(Long id,
                                     String name,
                                     RoutePolicy policy,
                                     ProtocolType outboundType,
                                     String host,
                                     Integer port) {
        RouteConfig route = new RouteConfig();
        route.setId(id);
        route.setName(name);
        route.setPolicy(policy);
        route.setOutboundProxyType(outboundType);
        route.setOutboundProxyHost(host);
        route.setOutboundProxyPort(port);
        route.setRules(List.of());
        return route;
    }

    private static RouteRule domainRule(String domain) {
        RouteRule rule = new RouteRule();
        rule.setConditionType(RouteConditionType.DOMAIN);
        rule.setOp(MatchOp.IN);
        rule.setValue(domain);
        return rule;
    }
}
