package org.congcong.proxyworker.protocol;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

public interface ProtocolStrategy {


    /**
     * 是否需要透明中继（RelayHandler）
     *  - SOCKS5 / HTTPS CONNECT / SS 等流式协议：true
     *  - DNS 这类请求/响应协议：false
     */
    default boolean needRelay() {
        return true;
    }

    void onConnectSuccess(ChannelHandlerContext inboundChannelContext, Channel outboundChannel, ProxyTunnelRequest request);

    /**
     * 连接失败时写回客户端错误响应。
     */
    void onConnectFailure(ChannelHandlerContext inboundChannelContext, Channel outboundChannel, ProxyTunnelRequest request, Throwable cause);
}
