package org.congcong.proxyworker.outbound;

import org.congcong.common.enums.RoutePolicy;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.outbound.block.BlockOutboundConnector;
import org.congcong.proxyworker.outbound.direct.DirectOutboundConnector;
import org.congcong.proxyworker.outbound.dns.DOTOutboundConnector;
import org.congcong.proxyworker.outbound.dns.DnsRewriteOutboundConnector;
import org.congcong.proxyworker.outbound.http.HttpProxyOutboundConnector;
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
        ProtocolType outboundType = route.getOutboundProxyType();
        // 消费路由策略
        // 要么是直接连接
        if (route.getPolicy() == RoutePolicy.DIRECT) {
            return new DirectOutboundConnector();
        }
        // 要么阻断
        if (route.getPolicy() == RoutePolicy.BLOCK) {
            return new BlockOutboundConnector();
        }
        // todo要么代替上游DNS服务器，返回一个特定的answer，返回的值为 route.getOutboundProxyHost()，暂时支持的是 IPV4
        if (route.getPolicy() == RoutePolicy.DNS_REWRITING) {
            return new DnsRewriteOutboundConnector();
        }
        // 要么是连接到其他服务器
        // 支持通过上游 HTTP CONNECT 代理（当前实现为未完成，返回失败）
        if (outboundType == ProtocolType.HTTPS_CONNECT) return new HttpProxyOutboundConnector();
        if (outboundType == ProtocolType.SOCKS5) return new Socks5OutboundConnector();
        if (outboundType == ProtocolType.SHADOW_SOCKS) return new ShadowSocksOutboundConnector();
        if (outboundType == ProtocolType.DOT)  return new DOTOutboundConnector();
        // 默认回退为直连，避免中断
        return new DirectOutboundConnector();
    }
}