package org.congcong.proxyworker.outbound.dns;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.dns.TcpDnsQueryEncoder;
import io.netty.handler.codec.dns.TcpDnsResponseDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.server.netty.tls.TlsClientContextManager;

/**
 * 出站是DOT的类型，会与目标DOT服务器建立TCP连接，连接成功后回调DnsOverTlsProtocolStrategy发送DNS查询请求
 */
@Slf4j
public class DOTOutboundConnector extends AbstractDnsUpstreamConnector {


    @Override
    protected int defaultPort() {
        return 853;
    }

    @Override
    protected ProtocolType outboundType() {
        return ProtocolType.DOT;
    }

    @Override
    protected boolean ready(Channel ch) {
        SslHandler ssl = ch.pipeline().get(SslHandler.class);
        // 仅在握手完成后才交付 relayPromise
        return ssl == null || ssl.handshakeFuture().isSuccess();
    }

    @Override
    protected ChannelFuture create(Channel inbound, String host, int port, PoolKey key) {
        Bootstrap b = new Bootstrap();
        b.group(inbound.eventLoop())
                .channel(getSocketChannel())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        SslContext clientContext = TlsClientContextManager.getInstance().getClientContext();
                        SslHandler sslHandler = clientContext.newHandler(ch.alloc(), host, port);
                        ch.pipeline().addLast(sslHandler);
                        ch.pipeline().addLast(new TcpDnsQueryEncoder());
                        ch.pipeline().addLast(new TcpDnsResponseDecoder());
                    }
                });

        ChannelFuture cf = b.connect(host, port);
        cf.addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.warn("DOT connect failed: {}", f.cause().getMessage());
            }
        });
        return cf;
    }
}
