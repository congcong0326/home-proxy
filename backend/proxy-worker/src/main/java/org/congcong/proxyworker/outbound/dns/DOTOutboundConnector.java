package org.congcong.proxyworker.outbound.dns;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.dns.TcpDnsQueryEncoder;
import io.netty.handler.codec.dns.TcpDnsResponseDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.outbound.AbstractOutboundConnector;
import org.congcong.proxyworker.server.netty.tls.TlsClientContextManager;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
public class DOTOutboundConnector extends AbstractOutboundConnector {



    private static final ConcurrentMap<PoolKey, ChannelFuture> CONNECTION_POOL = new ConcurrentHashMap<>();

    private record PoolKey(ProtocolType outboundType, String host, int port) {
        private PoolKey {
            Objects.requireNonNull(outboundType, "outboundType");
            Objects.requireNonNull(host, "host");
        }
    }

    @Override
    public ChannelFuture connect(Channel inboundChannel,
                                 ProxyTunnelRequest request,
                                 Promise<Channel> relayPromise) {
        // 上游 DoT 服务器地址：优先走 routeConfig，如果没有就用 targetHost/Port
        String host = request.getFinalTargetHost();
        int port = request.getFinalTargetPort() > 0 ? request.getFinalTargetPort() : 853;

        PoolKey key = new PoolKey(ProtocolType.DOT, host, port);
        ChannelFuture pooledFuture = CONNECTION_POOL.computeIfAbsent(
                key, k -> createNewConnection(inboundChannel, host, port, k));

        // 快捷路径：future 已完成且通道仍有效/握手成功，直接返回
        if (pooledFuture.isSuccess()) {
            Channel ch = pooledFuture.channel();
            if (ch != null && ch.isActive()) {
                SslHandler ssl = ch.pipeline().get(SslHandler.class);
                if (ssl == null || ssl.handshakeFuture().isSuccess()) {
                    relayPromise.trySuccess(ch);
                    return pooledFuture;
                }
            } else {
                // 已失效，重建
                CONNECTION_POOL.remove(key, pooledFuture);
                pooledFuture = CONNECTION_POOL.computeIfAbsent(
                        key, k -> createNewConnection(inboundChannel, host, port, k));
            }
        }

        // 常规路径：绑定监听，已完成也会立刻回调
        attachHandshake(pooledFuture, relayPromise, key);
        return pooledFuture;
    }

    private void handleHandshakeCompletion(Future<Channel> handshakeFuture,
                                           Promise<Channel> relayPromise,
                                           Channel channel,
                                           PoolKey key,
                                           ChannelFuture connectFuture) {
        if (handshakeFuture.isSuccess()) {
            log.debug("DOT TLS handshake success");
            relayPromise.trySuccess(channel);
        } else {
            log.warn("DOT TLS handshake failed: {}", handshakeFuture.cause().getMessage());
            CONNECTION_POOL.remove(key, connectFuture);
            relayPromise.tryFailure(handshakeFuture.cause());
            channel.close();
        }
    }

    private void attachHandshake(ChannelFuture connectFuture,
                                 Promise<Channel> relayPromise,
                                 PoolKey key) {
        connectFuture.addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                relayPromise.tryFailure(f.cause());
                return;
            }
            Channel ch = f.channel();
            SslHandler sslHandler = ch.pipeline().get(SslHandler.class);
            if (sslHandler == null) {
                relayPromise.trySuccess(ch);
                return;
            }
            Future<Channel> handshake = sslHandler.handshakeFuture();
            if (handshake.isDone()) {
                handleHandshakeCompletion(handshake, relayPromise, ch, key, f);
            } else {
                handshake.addListener(hf -> handleHandshakeCompletion((Future<Channel>) hf, relayPromise, ch, key, f));
            }
        });
    }

    private ChannelFuture createNewConnection(Channel inboundChannel,
                                              String host,
                                              int port,
                                              PoolKey key) {
        Bootstrap b = new Bootstrap();
        b.group(inboundChannel.eventLoop())
                .channel(getSocketChannel())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        TlsClientContextManager instance = TlsClientContextManager.getInstance();
                        SslContext clientContext = instance.getClientContext();
                        SslHandler sslHandler = clientContext.newHandler(ch.alloc(), host, port);
                        ch.pipeline().addLast(sslHandler);
                        ch.pipeline().addLast(new TcpDnsQueryEncoder());
                        ch.pipeline().addLast(new TcpDnsResponseDecoder());
                    }
                });

        ChannelFuture connectFuture = b.connect(host, port);
        connectFuture.addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.warn("DOT connect failed: {}", f.cause().getMessage());
                CONNECTION_POOL.remove(key, f);
                return;
            }
            // 连接关闭时移除池
            f.channel().closeFuture().addListener(cf -> CONNECTION_POOL.remove(key, f));
        });
        return connectFuture;
    }
}
