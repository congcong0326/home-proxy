package org.congcong.proxyworker.outbound;

import org.congcong.common.enums.RoutePolicy;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.outbound.direct.DirectOutboundConnector;
import org.congcong.proxyworker.outbound.http.HttpProxyOutboundConnector;
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
        if (route.getPolicy() == RoutePolicy.DIRECT) {
            return new DirectOutboundConnector();
        }
        // 支持通过上游 HTTP CONNECT 代理（当前实现为未完成，返回失败）
        if (outboundType == ProtocolType.HTTPS_CONNECT) return new HttpProxyOutboundConnector();
        if (outboundType == ProtocolType.SOCKS5) return new Socks5OutboundConnector();
        // if (outboundType == ProtocolType.SHADOWSOCKS) return new ShadowsocksOutboundConnector();
        // 默认回退为直连，避免中断
        return new DirectOutboundConnector();
    }
}