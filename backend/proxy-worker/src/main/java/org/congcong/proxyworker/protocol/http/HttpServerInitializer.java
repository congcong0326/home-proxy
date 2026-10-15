package org.congcong.proxyworker.protocol.http;

import io.netty.channel.Channel;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.server.netty.AbstractChannelInitializer;

public class HttpServerInitializer extends AbstractChannelInitializer {

    public HttpServerInitializer(InboundConfig inbound) {
        super(inbound);
    }

    @Override
    protected void init(Channel socketChannel) {
        socketChannel.pipeline().addLast(
                new HttpRequestDecoder(),
                new ResponseEncoder(),
                new HttpForwardHandler(),
                // 需要聚合下，如果一次没有解析出完整的http请求，容易导致后续流程报错
                new HttpObjectAggregator(1048576),
                HttpServerHandler.getInstance()
        );
    }

    // 隧道响应和中继数据已经是原始字节，不能再次按 HTTP content 编码。
    static final class ResponseEncoder extends HttpResponseEncoder {
        @Override
        public boolean acceptOutboundMessage(Object message) throws Exception {
            return !(message instanceof ByteBuf) && super.acceptOutboundMessage(message);
        }
    }
}
