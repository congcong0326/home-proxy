package org.congcong.proxyworker.protocol;

import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.protocol.dns.DnsOverTlsProtocolStrategy;
import org.congcong.proxyworker.protocol.dns.DnsRewritingProtocolStrategy;
import org.congcong.proxyworker.protocol.shadowsock.ShadowSocksProtocolStrategy;
import org.congcong.proxyworker.protocol.socks.Socks5ProtocolStrategy;
import org.congcong.proxyworker.protocol.http.HttpsConnectProtocolStrategy;
import org.congcong.proxyworker.protocol.transparent.TransparentProtocolStrategy;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

import java.util.EnumMap;
import java.util.Map;

public class ProtocolStrategyRegistry {


    private static final Map<ProtocolType, ProtocolStrategy> STRATEGIES = new EnumMap<>(ProtocolType.class);
    private static final DnsOverTlsProtocolStrategy dnsOverTlsProtocolStrategy = new DnsOverTlsProtocolStrategy();
    private static final DnsRewritingProtocolStrategy dnsRewritingProtocolStrategy = new DnsRewritingProtocolStrategy();
    static {
        STRATEGIES.put(ProtocolType.SOCKS5, new Socks5ProtocolStrategy());
        STRATEGIES.put(ProtocolType.HTTPS_CONNECT, new HttpsConnectProtocolStrategy());
        STRATEGIES.put(ProtocolType.SHADOW_SOCKS, new ShadowSocksProtocolStrategy());
        STRATEGIES.put(ProtocolType.TP_PROXY, new TransparentProtocolStrategy());
    }

    private ProtocolStrategyRegistry() {}

    private static ProtocolStrategy get(ProtocolType type) {
        return STRATEGIES.get(type);
    }

    public static ProtocolStrategy get(ProxyTunnelRequest proxyTunnelRequest) {
        ProtocolType protocol = proxyTunnelRequest.getInboundConfig().getProtocol();
        if (protocol == ProtocolType.DNS_SERVER) {
            RouteConfig routeConfig = proxyTunnelRequest.getRouteConfig();
            ProtocolType outboundProxyType = routeConfig.getOutboundProxyType();
            if (outboundProxyType == ProtocolType.DOT) {
                return dnsOverTlsProtocolStrategy;
            }
            RoutePolicy policy = routeConfig.getPolicy();
            if (policy == RoutePolicy.DNS_REWRITING) {
                return dnsRewritingProtocolStrategy;
            }
        }
        return get(protocol);
    }

}
