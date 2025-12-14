package org.congcong.proxyworker.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

/**
 * 在出站连接尚未建立时缓存客户端首包，带有大小保护。
 */
@Slf4j
public class RequestAppendHandler extends ChannelInboundHandlerAdapter {

    private final long maxPendingBytes;
    private CompositeByteBuf pendingPayload;
    private long bufferedBytes;
    private boolean connectFinished;

    public RequestAppendHandler(long maxPendingBytes) {
        this.maxPendingBytes = maxPendingBytes;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // 第一次请求
        if (msg instanceof ProxyTunnelRequest request) {
            ChannelAttributes.setProxyTunnelRequest(ctx.channel(), request);
            // 使用 CompositeByteBuf 暂存第一个负载包
            promoteExistingPayload(ctx, request);
            ctx.fireChannelRead(msg);
            return;
        }
        // 非ByteBuf的类直接传递到下游
        if (!(msg instanceof ByteBuf buf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        // 第二次请求来了
        ProxyTunnelRequest request = ChannelAttributes.getProxyTunnelRequest(ctx.channel());
        if (request == null || connectFinished) {
            ctx.fireChannelRead(buf);
            return;
        }
        // 此时目标服务器还未连接上
        try {
            CompositeByteBuf payload = ensurePendingBuffer(ctx, request);
            // 将第二次负载通过CompositeByteBuf拼接，减少拷贝
            payload.addComponent(true, buf.retain());
            bufferedBytes += buf.readableBytes();
            if (bufferedBytes > maxPendingBytes) {
                log.warn("initial payload exceeds limit {} bytes, closing {}", maxPendingBytes, ctx.channel());
                cleanupPayload(request);
                ctx.close();
            }
        } finally {
            // 这种写法可有可无，只是语义上更加明确，入站的buf我已经release，但是传递给CompositeByteBuf的时候我retain了一份
            ReferenceCountUtil.release(buf);
        }
    }

    /**
     * 连接完成（成功或失败）后由下游调用，移除自身。
     */
    public void onConnectComplete(ChannelHandlerContext ctx) {
        connectFinished = true;
        if (ctx.pipeline().context(this) != null) {
            ctx.pipeline().remove(this);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        ProxyTunnelRequest req = ChannelAttributes.getProxyTunnelRequest(ctx.channel());
        if (req != null) {
            cleanupPayload(req);
            ChannelAttributes.removeProxyTunnelRequest(ctx.channel());
        }
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        super.handlerRemoved(ctx);
    }

    private CompositeByteBuf ensurePendingBuffer(ChannelHandlerContext ctx, ProxyTunnelRequest request) {
        if (pendingPayload == null) {
            pendingPayload = ctx.alloc().compositeBuffer();
            request.setInitialPayload(pendingPayload);
        }
        return pendingPayload;
    }

    private void promoteExistingPayload(ChannelHandlerContext ctx, ProxyTunnelRequest request) {
        ByteBuf existing = request.getInitialPayload();
        if (existing == null) {
            return;
        }
        if (existing instanceof CompositeByteBuf) {
            pendingPayload = (CompositeByteBuf) existing;
            bufferedBytes = pendingPayload.readableBytes();
            return;
        }
        CompositeByteBuf composite = ctx.alloc().compositeBuffer();
        composite.addComponent(true, existing);
        pendingPayload = composite;
        bufferedBytes = existing.readableBytes();
        request.setInitialPayload(composite);
    }

    private void cleanupPayload(ProxyTunnelRequest request) {
        if (pendingPayload != null) {
            if (pendingPayload.refCnt() > 0) {
                pendingPayload.release();
            }
        } else {
            ByteBuf payload = request.getInitialPayload();
            if (payload != null && payload.refCnt() > 0) {
                payload.release();
            }
        }
        pendingPayload = null;
        request.setInitialPayload(null);
        bufferedBytes = 0;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        ProxyTunnelRequest request = ChannelAttributes.getProxyTunnelRequest(ctx.channel());
        if (request != null) {
            cleanupPayload(request);
        }
        super.exceptionCaught(ctx, cause);
    }
}
