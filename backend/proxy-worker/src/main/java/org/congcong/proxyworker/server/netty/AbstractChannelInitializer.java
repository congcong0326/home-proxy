package org.congcong.proxyworker.server.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.ProxyTimeContext;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.protocol.ProxyTunnelConnectorHandler;
import org.congcong.proxyworker.protocol.RequestAppendHandler;
import org.congcong.proxyworker.router.RouterService;
import org.congcong.proxyworker.util.ProxyContextFillUtil;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import org.congcong.proxyworker.server.netty.tls.TlsContextManager;

import java.util.Objects;


public abstract class AbstractChannelInitializer extends ChannelInitializer<Channel> {

    protected final InboundConfig inboundConfig;

    protected AbstractChannelInitializer(InboundConfig inboundConfig) {
        this.inboundConfig = inboundConfig;
    }

    @Override
    protected void initChannel(Channel ch) throws Exception {
        pipeLineContextInit(ch);
        // 在最前面处理 TLS（如启用）
        processSSL(ch);
        // 添加根据各个协议的channelHandler
        // 认证相关的处理器
        // ch.pipeline().addFirst(new LoggingHandler(LogLevel.DEBUG));
        init(ch);
        // 路由处理器
        ch.pipeline().addLast(RouterService.getInstance());
        // 处理握手请求还携带payload的场景，每个连接在连上目标服务器前最多暂存1M
        ch.pipeline().addLast(new RequestAppendHandler(1_048_576));
        // 连接目标服务器
        ch.pipeline().addLast(ProxyTunnelConnectorHandler.getInstance());
        // 兜底异常消费
        ch.pipeline().addLast(TerminalExceptionHandler.getInstance());
    }

    protected abstract void init(Channel socketChannel);


    protected void processSSL(Channel socketChannel) {
        // 开启TLS
        if (Objects.equals(inboundConfig.getTlsEnabled(), true) && inboundConfig.getProtocol() != ProtocolType.SHADOW_SOCKS) {
            SslContext sslContext = TlsContextManager.getInstance().getServerContext(inboundConfig);
            SslHandler sslHandler = sslContext.newHandler(socketChannel.alloc());
            socketChannel.pipeline().addFirst("ssl", sslHandler);
        }
    }

    protected void pipeLineContextInit(Channel socketChannel) {
        ChannelAttributes.setInboundConfig(socketChannel, inboundConfig);
        // 生成上下文对象
        ProxyContext proxyContext = new ProxyContext();
        // 填写默认值
        ProxyContextFillUtil.proxyContextInitFill(socketChannel, inboundConfig, proxyContext);

        ChannelAttributes.setProxyContext(socketChannel, proxyContext);

        ProxyTimeContext proxyTimeContext = new ProxyTimeContext();

        ChannelAttributes.setProxyTimeContext(socketChannel, proxyTimeContext);
    }
}
