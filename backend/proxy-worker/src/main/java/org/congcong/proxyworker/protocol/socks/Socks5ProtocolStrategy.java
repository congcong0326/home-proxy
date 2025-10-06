package org.congcong.proxyworker.protocol.socks;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.congcong.proxyworker.protocol.ProtocolStrategy;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

public class Socks5ProtocolStrategy implements ProtocolStrategy {


    @Override
    public void onConnectSuccess(ChannelHandlerContext inboundChannelContext, Channel outboundChannel, ProxyTunnelRequest request) {
        String bndAddr = "0.0.0.0";
        int bndPort = 0;
        Socks5AddressType addrType = Socks5AddressType.IPv4;

        try {
            if (outboundChannel != null && outboundChannel.localAddress() instanceof InetSocketAddress) {
                InetSocketAddress local = (InetSocketAddress) outboundChannel.localAddress();
                InetAddress addr = local.getAddress();
                bndPort = local.getPort();
                if (addr != null) {
                    bndAddr = addr.getHostAddress();
                    addrType = (addr instanceof Inet6Address) ? Socks5AddressType.IPv6 : Socks5AddressType.IPv4;
                }
            }
        } catch (Exception ignore) {}

        DefaultSocks5CommandResponse ok = new DefaultSocks5CommandResponse(
                Socks5CommandStatus.SUCCESS,
                addrType,
                bndAddr,
                bndPort
        );
        inboundChannelContext.writeAndFlush(ok);
    }

    @Override
    public void onConnectFailure(ChannelHandlerContext inboundChannelContext, Channel outboundChannel, ProxyTunnelRequest request, Throwable cause) {
        DefaultSocks5CommandResponse fail = new DefaultSocks5CommandResponse(
                Socks5CommandStatus.FAILURE,
                Socks5AddressType.IPv4
        );
        inboundChannelContext.writeAndFlush(fail);
    }
}
