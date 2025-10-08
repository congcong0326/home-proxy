package org.congcong.proxyworker.server.netty;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.ProxyTimeContext;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.protocol.RequestAppendHandler;
import org.congcong.proxyworker.protocol.TcpTunnelConnectorHandler;
import org.congcong.proxyworker.router.RouterService;
import org.congcong.proxyworker.util.ProxyContextFillUtil;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import org.congcong.proxyworker.server.netty.tls.TlsContextManager;

import java.util.Objects;


public abstract class AbstractChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final InboundConfig inboundConfig;

    protected AbstractChannelInitializer(InboundConfig inboundConfig) {
        this.inboundConfig = inboundConfig;
    }

    @Override
    protected void initChannel(SocketChannel socketChannel) throws Exception {
        pipeLineContextInit(socketChannel);
        // 在最前面处理 TLS（如启用）
        processSSL(socketChannel);
        socketChannel.pipeline().addLast(
                // 添加一些统计的channelHandler
                //new LoggingHandler(LogLevel.INFO),
                );
        // 添加根据各个协议的channelHandler
        // 认证相关的处理器
        init(socketChannel);
        // 路由处理器
        socketChannel.pipeline().addLast(RouterService.getInstance());
        // 处理握手请求还携带payload的场景
        socketChannel.pipeline().addLast(RequestAppendHandler.getInstance());
        // 连接目标服务器
        socketChannel.pipeline().addLast(TcpTunnelConnectorHandler.getInstance());
    }

    protected abstract void init(SocketChannel socketChannel);

    private void processSSL(SocketChannel socketChannel) {
        // 开启TLS
        if (Objects.equals(inboundConfig.getTlsEnabled(), true) && inboundConfig.getProtocol() != ProtocolType.SHADOW_SOCKS) {
            SslContext sslContext = TlsContextManager.getInstance().getServerContext(inboundConfig);
            SslHandler sslHandler = sslContext.newHandler(socketChannel.alloc());
            socketChannel.pipeline().addFirst("ssl", sslHandler);
        }
    }

    private void pipeLineContextInit(SocketChannel socketChannel) {
        ChannelAttributes.setInboundConfig(socketChannel, inboundConfig);
        // 构建并填充代理上下文
        ProxyContext context = new ProxyContext();
        ProxyContextFillUtil.proxyContextInitFill(inboundConfig, context);
        ChannelAttributes.setProxyContext(socketChannel, context);
        ChannelAttributes.setProxyTimeContext(socketChannel, new ProxyTimeContext());
    }
}
