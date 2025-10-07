package org.congcong.proxyworker.server.netty;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.ProxyTimeContext;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.protocol.TcpTunnelConnectorHandler;
import org.congcong.proxyworker.router.RouterService;
import org.congcong.proxyworker.util.ProxyContextFillUtil;


public abstract class AbstractChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final InboundConfig inboundConfig;

    protected AbstractChannelInitializer(InboundConfig inboundConfig) {
        this.inboundConfig = inboundConfig;
    }

    @Override
    protected void initChannel(SocketChannel socketChannel) throws Exception {
        pipeLineContextInit(socketChannel);
        socketChannel.pipeline().addLast(
                // 添加一些统计的channelHandler
                //new LoggingHandler(LogLevel.INFO),
                );
        // 添加根据各个协议的channelHandler
        // 认证相关的处理器
        init(socketChannel);
        socketChannel.pipeline().addLast(RouterService.getInstance());
        socketChannel.pipeline().addLast(TcpTunnelConnectorHandler.getInstance());
    }

    protected abstract void init(SocketChannel socketChannel);


    private void pipeLineContextInit(SocketChannel socketChannel) {
        ChannelAttributes.setInboundConfig(socketChannel, inboundConfig);
        // 构建并填充代理上下文
        ProxyContext context = new ProxyContext();
        ProxyContextFillUtil.proxyContextInitFill(inboundConfig, context);
        ChannelAttributes.setProxyContext(socketChannel, context);
        ChannelAttributes.setProxyTimeContext(socketChannel, new ProxyTimeContext());
    }
}
