package org.congcong.proxyworker;

import io.netty.handler.codec.dns.DnsRecordType;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.server.tunnel.DnsProxyContext;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

public final class ProxyWorkerTestFixtures {
    private ProxyWorkerTestFixtures() {
    }

    public static UserConfig user(long id, String username, String credential, String ipAddress) {
        UserConfig user = new UserConfig();
        user.setId(id);
        user.setUsername(username);
        user.setCredential(credential);
        user.setIpAddress(ipAddress);
        return user;
    }

    public static RouteConfig route(RoutePolicy policy, ProtocolType outboundType, String host, Integer port) {
        RouteConfig route = new RouteConfig();
        route.setId(10L);
        route.setName(policy.name().toLowerCase());
        route.setPolicy(policy);
        route.setOutboundProxyType(outboundType);
        route.setOutboundProxyHost(host);
        route.setOutboundProxyPort(port);
        route.setRules(List.of());
        return route;
    }

    public static InboundConfig inbound(ProtocolType protocol) {
        UserConfig anonymous = user(0L, "anonymous", null, null);
        InboundConfig inbound = new InboundConfig();
        inbound.setId(100L);
        inbound.setName(protocol.name().toLowerCase() + "-inbound");
        inbound.setProtocol(protocol);
        inbound.setListenIp("127.0.0.1");
        inbound.setPort(1080);
        inbound.setTlsEnabled(false);
        inbound.setSniffEnabled(true);
        inbound.setUsersMap(new HashMap<>());
        inbound.setDeviceIpMapUser(new HashMap<>());
        inbound.setRoutesMap(new HashMap<>());
        inbound.setAnonymousUser(anonymous);
        inbound.setDefaultRouteConfig(route(RoutePolicy.DIRECT, ProtocolType.NONE, null, null));
        return inbound;
    }

    public static InboundConfig socksInbound(UserConfig user) {
        InboundConfig inbound = inbound(ProtocolType.SOCKS5);
        inbound.setUsersMap(Map.of(user.getUsername(), user));
        inbound.setRoutesMap(Map.of(user.getId(), List.of(inbound.getDefaultRouteConfig())));
        return inbound;
    }

    public static InboundConfig deviceInbound(ProtocolType protocol, UserConfig user) {
        InboundConfig inbound = inbound(protocol);
        inbound.setDeviceIpMapUser(Map.of(user.getIpAddress(), user));
        inbound.setRoutesMap(Map.of(user.getId(), List.of(inbound.getDefaultRouteConfig())));
        return inbound;
    }

    public static ProxyContext proxyContext(String clientIp, String targetIp, int targetPort) {
        ProxyContext context = new ProxyContext();
        context.setClientIp(clientIp);
        context.setClientPort(12345);
        context.setOriginalTargetIP(targetIp);
        context.setOriginalTargetPort(targetPort);
        return context;
    }

    public static ProxyTunnelRequest dnsRequest(InboundConfig inbound,
                                                UserConfig user,
                                                int inboundId,
                                                String qName,
                                                DnsRecordType qType,
                                                InetSocketAddress client) {
        DnsProxyContext dnsCtx = new DnsProxyContext(inboundId, qName, qType, client);
        return new ProxyTunnelRequest(ProtocolType.DNS_SERVER, qName, 53, user, inbound, dnsCtx);
    }

    public static InetSocketAddress socket(String ip, int port) {
        try {
            return new InetSocketAddress(InetAddress.getByName(ip), port);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid test IP: " + ip, e);
        }
    }
}
