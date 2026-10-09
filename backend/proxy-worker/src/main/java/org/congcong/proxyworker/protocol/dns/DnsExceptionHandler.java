package org.congcong.proxyworker.protocol.dns;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/** A request/decoder failure must not close the UDP listener shared by all clients. */
@Slf4j
@ChannelHandler.Sharable
final class DnsExceptionHandler extends ChannelInboundHandlerAdapter {
    static final DnsExceptionHandler INSTANCE = new DnsExceptionHandler();

    private DnsExceptionHandler() {}

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof IOException) {
            log.warn("DNS 监听发生 I/O 异常，关闭失效通道: {}", cause.toString());
            ctx.close();
        } else {
            // DecoderException can wrap an IOException for an invalid datagram;
            // that is a bad packet, not a failure of the listening socket.
            log.warn("丢弃处理失败的 DNS 请求，保持 UDP 监听: {}", cause.toString());
        }
    }
}
