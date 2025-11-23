package org.congcong.proxyworker.protocol.http;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
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
                // 需要聚合下，如果一次没有解析出完整的http请求，容易导致后续流程报错
                new HttpObjectAggregator(1048576),
                HttpServerHandler.getInstance()
        );
    }

}
