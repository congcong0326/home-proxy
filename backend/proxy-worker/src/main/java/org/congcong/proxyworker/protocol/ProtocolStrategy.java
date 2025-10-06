package org.congcong.proxyworker.protocol;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

public interface ProtocolStrategy {


    void onConnectSuccess(ChannelHandlerContext inboundChannelContext, Channel outboundChannel, ProxyTunnelRequest request);

    /**
     * 连接失败时写回客户端错误响应。
     */
    void onConnectFailure(ChannelHandlerContext inboundChannelContext, Channel outboundChannel, ProxyTunnelRequest request, Throwable cause);
}
