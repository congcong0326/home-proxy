package org.congcong.proxyworker.udp;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class TransparentUdpInboundHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    private final UdpSessionManager sessions;

    public TransparentUdpInboundHandler(UdpSessionManager sessions) { this.sessions = sessions; }

    @Override protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
        sessions.receive(packet.sender(), packet.recipient(), packet.content().retain());
    }

    @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        sessions.close();
        super.channelInactive(ctx);
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Transparent UDP listener error", cause);
    }
}
