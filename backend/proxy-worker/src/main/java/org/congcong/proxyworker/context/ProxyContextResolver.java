package org.congcong.proxyworker.context;

import io.netty.channel.Channel;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.ProxyTimeContext;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

/**
 * 统一获取代理上下文的解析器：优先使用请求级上下文，缺失时回退到 channel 级别，
 * 并将解析结果回写到请求，避免各处手动判空。
 */
public final class ProxyContextResolver {

    private ProxyContextResolver() {}

    public static ProxyContext resolveProxyContext(Channel channel, ProxyTunnelRequest request) {
        if (request != null && request.getProxyContext() != null) {
            return request.getProxyContext();
        }
        ProxyContext ctx = ChannelAttributes.getProxyContext(channel);
        if (request != null && ctx != null) {
            request.setProxyContext(ctx);
        }
        return ctx;
    }

    public static ProxyTimeContext resolveProxyTimeContext(Channel channel, ProxyTunnelRequest request) {
        if (request != null && request.getProxyTimeContext() != null) {
            return request.getProxyTimeContext();
        }
        ProxyTimeContext timeContext = ChannelAttributes.getProxyTimeContext(channel);
        if (request != null && timeContext != null) {
            request.setProxyTimeContext(timeContext);
        }
        return timeContext;
    }
}
