package org.congcong.proxyworker.protocol.transparent;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.ProxyContext;
import org.congcong.proxyworker.dns.FakeIpRegistry;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.LogicalTargetSource;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Restores a mapped domain before reading payload and bounds fallback sniffing for old leases. */
@Slf4j
final class FakeIpTransparentHandler extends ChannelInboundHandlerAdapter {
    private static final int MAX_SNIFF_BYTES = 64 * 1024;
    private static final int SNIFF_TIMEOUT_SECONDS = 3;
    private ScheduledFuture<?> timeout;
    private boolean awaitingSniff;
    private int receivedBytes;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ProxyContext proxyContext = ChannelAttributes.getProxyContext(ctx.channel());
        String originalIp = proxyContext == null ? null : proxyContext.getOriginalTargetIP();
        if (!FakeIpRegistry.isInPool(originalIp)) {
            ctx.fireChannelActive();
            return;
        }
        Optional<String> mapped = FakeIpRegistry.getInstance().reverseLookup(originalIp);
        if (mapped.isPresent()) {
            proxyContext.setOriginalTargetHost(mapped.get());
            ctx.fireChannelActive();
            ctx.pipeline().remove(ProtocolDetectHandler.class);
            ctx.pipeline().remove(TransparentServerHandler.class);
            try {
                ProxyTunnelRequest request = TransparentServerHandler.createRequest(ctx, mapped.get(), true,
                        null, LogicalTargetSource.FAKE_IP);
                TransparentServerHandler.fireRequest(ctx, request);
            } catch (RuntimeException failure) {
                log.warn("Fake IP 反查后建立请求失败: ip={}, reason={}", originalIp, failure.getMessage());
                ctx.close();
            }
            return;
        }
        awaitingSniff = true;
        timeout = ctx.executor().schedule(() -> {
            if (awaitingSniff) {
                log.info("Fake IP 映射未命中且嗅探超时: ip={}", originalIp);
                ctx.close();
            }
        }, SNIFF_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        ctx.fireChannelActive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
        if (awaitingSniff && message instanceof ByteBuf bytes) {
            receivedBytes += bytes.readableBytes();
            if (receivedBytes > MAX_SNIFF_BYTES) {
                log.info("Fake IP 映射未命中且嗅探缓冲超限: bytes={}", receivedBytes);
                bytes.release();
                ctx.close();
                return;
            }
        }
        ctx.fireChannelRead(message);
    }

    void complete() {
        awaitingSniff = false;
        if (timeout != null) timeout.cancel(false);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        complete();
        ctx.fireChannelInactive();
    }
}
