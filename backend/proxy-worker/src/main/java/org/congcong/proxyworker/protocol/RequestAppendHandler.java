package org.congcong.proxyworker.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

@Slf4j
@ChannelHandler.Sharable
public class RequestAppendHandler extends ChannelInboundHandlerAdapter {

    private RequestAppendHandler() {

    }
    public static RequestAppendHandler getInstance() {
        return RequestAppendHandler.Holder.INSTANCE;
    }

    private static class Holder {
        private static final RequestAppendHandler INSTANCE = new RequestAppendHandler();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ProxyTunnelRequest request) {
            ChannelAttributes.setProxyTunnelRequest(ctx.channel(), request);
            request.setStatus(ProxyTunnelRequest.Status.append);
            ctx.fireChannelRead(msg);
            return;
        }
        if (msg instanceof ByteBuf buf) {
            ProxyTunnelRequest request = ChannelAttributes.getProxyTunnelRequest(ctx.channel());
            try {
                if (request != null) {
                    if (request.getStatus() == ProxyTunnelRequest.Status.append) {
                        ByteBuf initialPayload = request.getInitialPayload();
                        if (initialPayload != null) {
                            if (initialPayload.writableBytes() < buf.readableBytes()) {
                                ByteBuf expanded = ctx.alloc().buffer(initialPayload.readableBytes() +
                                        buf.readableBytes());
                                expanded.writeBytes(initialPayload);
                                initialPayload.release();
                                initialPayload = expanded;
                                request.setInitialPayload(expanded);
                            }
                            initialPayload.writeBytes(buf);
                        } else {
                            ByteBuf copy = ctx.alloc().buffer(buf.readableBytes()).writeBytes(buf);
                            request.setInitialPayload(copy);
                        }
                        return; // 已处理完入站字节
                    }
                    if (request.getStatus() == ProxyTunnelRequest.Status.finish) {
                        ChannelAttributes.removeProxyTunnelRequest(ctx.channel());
                        ctx.pipeline().remove(this);
                        ctx.fireChannelRead(buf.retain()); // 向后传递，但持有一份引用
                        return;
                    }
                }
            } finally {
                ReferenceCountUtil.release(buf); // 确保入站 ByteBuf 释放
            }
            return;
        }
        ctx.fireChannelRead(msg);
    }




}
