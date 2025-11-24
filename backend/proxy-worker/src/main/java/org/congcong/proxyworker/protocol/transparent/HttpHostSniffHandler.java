package org.congcong.proxyworker.protocol.transparent;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.CharsetUtil;
import org.congcong.common.dto.ProxyContext;
import org.congcong.proxyworker.server.netty.ChannelAttributes;

@ChannelHandler.Sharable
public class HttpHostSniffHandler extends ChannelInboundHandlerAdapter {

    private HttpHostSniffHandler() {

    }

    public static HttpHostSniffHandler getInstance() {
        return Holder.INSTANCE;
    }

    private static class Holder {
        private static final HttpHostSniffHandler INSTANCE = new HttpHostSniffHandler();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf buf) {
            int readable = buf.readableBytes();
            if (readable > 0) {
                String text = buf.toString(buf.readerIndex(),
                        Math.min(readable, 2048), // 只看前 2KB，够放头部了
                        CharsetUtil.US_ASCII);

                int hostIdx = text.indexOf("\r\nHost:");
                if (hostIdx >= 0) {
                    int start = hostIdx + "\r\nHost:".length();
                    int end = text.indexOf("\r\n", start);
                    if (end > start) {
                        String host = text.substring(start, end).trim();
                        ProxyContext proxyContext = ChannelAttributes.getProxyContext(ctx.channel());
                        proxyContext.setOriginalTargetHost(host);
                    }
                }
            }
        }
        ctx.pipeline().remove(this);
        // 不要吃掉数据，原样传下去
        super.channelRead(ctx, msg);
    }

}
