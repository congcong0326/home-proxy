package org.congcong.proxyworker.server.netty;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.router.RouterService;


public abstract class AbstractChannelInitializer extends ChannelInitializer<SocketChannel> {

    private InboundConfig inboundConfig;

    protected AbstractChannelInitializer(InboundConfig inboundConfig) {
        this.inboundConfig = inboundConfig;
    }

    @Override
    protected void initChannel(SocketChannel socketChannel) throws Exception {
        ChannelAttributes.setInboundConfig(socketChannel, inboundConfig);
        socketChannel.pipeline().addLast(
                // 添加一些统计的channelHandler
                //new LoggingHandler(LogLevel.INFO),
                );
        // 添加根据各个协议的channelHandler
        // 认证相关的处理器
        init(socketChannel);
        socketChannel.pipeline().addLast(RouterService.getInstance());
    }

    protected abstract void init(SocketChannel socketChannel);
}
