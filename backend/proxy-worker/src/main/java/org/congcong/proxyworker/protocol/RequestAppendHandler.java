package org.congcong.proxyworker.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
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
        // 第一次请求
        if (msg instanceof ProxyTunnelRequest request) {
            ChannelAttributes.setProxyTunnelRequest(ctx.channel(), request);
            request.setStatus(ProxyTunnelRequest.Status.append);
            ctx.fireChannelRead(msg);
            return;
        }
        // 第二次请求
        if (msg instanceof ByteBuf buf) {
            ProxyTunnelRequest request = ChannelAttributes.getProxyTunnelRequest(ctx.channel());
            if (request != null) {
                ProxyTunnelRequest.Status status = request.getStatus();
                // 此时与目标服务器地址的连接未建立成功，需要将收到的数据追加到request的initialPayload中
                // 只有第一次请求在一次没接收完成时才会出现该情况
                if (status == ProxyTunnelRequest.Status.append) {
                    ByteBuf initialPayload = request.getInitialPayload();
                    if (initialPayload != null) {
                        if (initialPayload.writableBytes() < buf.readableBytes()) {
                            ByteBuf expanded = ctx.alloc().buffer(initialPayload.readableBytes() + buf.readableBytes());
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
                    //ctx.fireChannelRead(msg);
                    return;
                }
                // 有可能第二次数据包过来，客户端连接已经建立，此时需要移除第一次请求的对象，并且向下传播请求
                if (request.getStatus() == ProxyTunnelRequest.Status.finish) {
                    ChannelAttributes.removeProxyTunnelRequest(ctx.channel());
                    ctx.pipeline().remove(this);
                    ctx.fireChannelRead(msg);
                    return;
                }
            }
        }
        // 其他情况一律传递
        ctx.fireChannelRead(msg);
    }


}
