package org.congcong.proxyworker.outbound.dns;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.outbound.AbstractOutboundConnector;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public abstract class AbstractDnsUpstreamConnector extends AbstractOutboundConnector {

    private static final ConcurrentMap<PoolKey, ChannelFuture> POOL = new ConcurrentHashMap<>();

    protected record PoolKey(ProtocolType type, String host, int port) {
    }

    @Override
    public ChannelFuture connect(Channel inbound,
                                 ProxyTunnelRequest req,
                                 Promise<Channel> relayPromise) {
        RouteConfig routeConfig = req.getRouteConfig();
        String host = routeConfig.getOutboundProxyHost();
        int port = routeConfig.getOutboundProxyPort() != null ? routeConfig.getOutboundProxyPort() : defaultPort();
        // 使用远端DNS服务器类型+IP+PORT组合为唯一三元组
        PoolKey key = new PoolKey(outboundType(), host, port);
        // 创建了相关的客户端
        // 使用 POOL 维护三元组到目标DNS服务器之间的映射关系，在客户端销毁后去会同步回收
        ChannelFuture pooled = POOL.computeIfAbsent(key, k -> {
            ChannelFuture cf = create(inbound, host, port, k);
            cf.addListener((ChannelFutureListener) f -> {
                if (f.isSuccess()) {
                    f.channel().closeFuture().addListener(close -> POOL.remove(k, f));
                } else {
                    POOL.remove(k, f);
                }
            });
            return cf;
        });
        // 客户端已经就绪，则可以直接发送请求
        if (pooled.isSuccess() && pooled.channel().isActive() && ready(pooled.channel())) {
            if (outboundType() == ProtocolType.DOT) {
                // DOT要等待SSL握手完成，注册回调在SSL完成后发送请求
                awaitReady(pooled, relayPromise, key);
            } else {
                // DNS没有握手请求，只要就绪可以直接发送
                relayPromise.trySuccess(pooled.channel());
            }
            return pooled;
        }

        // 客户端也许还未就绪，则注册回调，未来发送
        pooled.addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                relayPromise.tryFailure(f.cause());
                POOL.remove(key, f);
                return;
            }
            if (outboundType() == ProtocolType.DOT) {
                awaitReady(f, relayPromise, key);
            } else {
                relayPromise.trySuccess(f.channel());
            }
        });
        return pooled;
    }

    protected abstract int defaultPort();           // 53 for UDP, 853 for DoT
    protected abstract ProtocolType outboundType(); // DNS_SERVER / DOT
    protected abstract boolean ready(Channel ch);   // DoT 要求 TLS 握手完成
    protected abstract ChannelFuture create(Channel inbound, String host, int port, PoolKey key);

    private void awaitReady(ChannelFuture connected,
                            Promise<Channel> relayPromise,
                            PoolKey key) {
        Channel ch = connected.channel();
        if (ready(ch)) {
            relayPromise.trySuccess(ch);
            return;
        }

        SslHandler ssl = ch.pipeline().get(SslHandler.class);
        if (ssl != null) {
            Future<Channel> handshake = ssl.handshakeFuture();
            handshake.addListener(f -> {
                if (f.isSuccess() && ready(ch)) {
                    relayPromise.trySuccess(ch);
                } else {
                    Throwable cause = f.cause() != null ? f.cause() :
                            new IllegalStateException("TLS handshake not ready");
                    relayPromise.tryFailure(cause);
                    POOL.remove(key, connected);
                    ch.close();
                }
            });
        } else {
            relayPromise.tryFailure(new IllegalStateException("Channel not ready"));
            POOL.remove(key, connected);
            ch.close();
        }
    }
}
