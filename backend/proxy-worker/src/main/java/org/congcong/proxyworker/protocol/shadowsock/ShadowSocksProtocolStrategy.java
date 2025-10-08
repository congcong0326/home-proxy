package org.congcong.proxyworker.protocol.shadowsock;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.congcong.proxyworker.protocol.ProtocolStrategy;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

public class ShadowSocksProtocolStrategy implements ProtocolStrategy  {
    @Override
    public void onConnectSuccess(ChannelHandlerContext inboundChannelContext, Channel outboundChannel, ProxyTunnelRequest request) {

    }

    @Override
    public void onConnectFailure(ChannelHandlerContext inboundChannelContext, Channel outboundChannel, ProxyTunnelRequest request, Throwable cause) {

    }
}
