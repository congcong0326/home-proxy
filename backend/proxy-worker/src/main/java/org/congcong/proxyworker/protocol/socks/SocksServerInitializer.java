package org.congcong.proxyworker.protocol.socks;

import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.server.netty.AbstractChannelInitializer;

public class SocksServerInitializer extends AbstractChannelInitializer {
    public SocksServerInitializer(InboundConfig inboundConfig) {
        super(inboundConfig);
    }

    @Override
    protected void init(SocketChannel socketChannel) {
        socketChannel.pipeline().addLast(
                Socks5ServerEncoder.DEFAULT,
                new Socks5InitialRequestDecoder(),
                SocksServerHandler.getInstance());
    }
}
