package org.congcong.proxyworker.protocol.tlssni;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.congcong.proxyworker.protocol.ProtocolStrategy;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

/** TLS SNI TCP tunnelling has no protocol-level error frame. */
public class TlsSniProtocolStrategy implements ProtocolStrategy {

    @Override
    public void onConnectSuccess(ChannelHandlerContext inboundChannelContext, Channel outboundChannel, ProxyTunnelRequest request) {
        // The retained ClientHello is relayed by the common connector handler.
    }

    @Override
    public void onConnectFailure(ChannelHandlerContext inboundChannelContext, Channel outboundChannel,
                                 ProxyTunnelRequest request, Throwable cause) {
        inboundChannelContext.close();
    }
}
