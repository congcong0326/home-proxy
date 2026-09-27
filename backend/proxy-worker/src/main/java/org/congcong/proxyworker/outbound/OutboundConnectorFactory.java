package org.congcong.proxyworker.outbound;

import org.congcong.common.enums.RoutePolicy;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.outbound.block.BlockOutboundConnector;
import org.congcong.proxyworker.outbound.direct.DirectOutboundConnector;
import org.congcong.proxyworker.outbound.dns.DNSConnector;
import org.congcong.proxyworker.outbound.dns.DOTOutboundConnector;
import org.congcong.proxyworker.outbound.dns.DnsRewriteOutboundConnector;
import org.congcong.proxyworker.outbound.http.HttpProxyOutboundConnector;
import org.congcong.proxyworker.outbound.reality.VlessRealityOutboundConnector;
import org.congcong.proxyworker.outbound.shadowsocks.ShadowSocksOutboundConnector;
import org.congcong.proxyworker.outbound.socks.Socks5OutboundConnector;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

/**
 * 出站连接器工厂：按路由策略与出站类型选择连接器。
 */
public final class OutboundConnectorFactory {

    private OutboundConnectorFactory() {}

    public static OutboundConnector create(ProxyTunnelRequest request) {
        RouteConfig route = request.getRouteConfig();
        if (request.getDestinationAddressBlocked() != null) {
            return new BlockOutboundConnector(request.getDestinationAddressBlocked());
        }
        if (route == null || route.getPolicy() == null) {
            return new BlockOutboundConnector();
        }
        return switch (route.getPolicy()) {
            case DIRECT -> new DirectOutboundConnector();
            // An override is an explicit administrator-trusted destination;
            // DirectOutboundConnector intentionally skips public validation
            // unless the policy is DIRECT.
            case DESTINATION_OVERRIDE -> hasOutboundEndpoint(route)
                    ? new DirectOutboundConnector()
                    : new BlockOutboundConnector();
            case BLOCK -> new BlockOutboundConnector();
            case DNS_REWRITING -> new DnsRewriteOutboundConnector();
            case OUTBOUND_PROXY -> createOutboundProxyConnector(route);
        };
    }

    private static OutboundConnector createOutboundProxyConnector(RouteConfig route) {
        if (!hasOutboundEndpoint(route) || route.getOutboundProxyType() == null) {
            return new BlockOutboundConnector();
        }
        return switch (route.getOutboundProxyType()) {
            case HTTPS_CONNECT -> new HttpProxyOutboundConnector();
            case SOCKS5 -> new Socks5OutboundConnector();
            case SHADOW_SOCKS -> new ShadowSocksOutboundConnector();
            case VLESS_REALITY -> new VlessRealityOutboundConnector();
            case DOT -> new DOTOutboundConnector();
            case DNS_SERVER -> new DNSConnector();
            default -> new BlockOutboundConnector();
        };
    }

    private static boolean hasOutboundEndpoint(RouteConfig route) {
        return route.getOutboundProxyHost() != null && !route.getOutboundProxyHost().isBlank()
                && route.getOutboundProxyPort() != null
                && route.getOutboundProxyPort() > 0
                && route.getOutboundProxyPort() <= 65535;
    }
}
