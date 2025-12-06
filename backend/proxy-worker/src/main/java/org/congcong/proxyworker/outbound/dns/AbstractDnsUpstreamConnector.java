package org.congcong.proxyworker.outbound.dns;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.concurrent.Promise;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.outbound.AbstractOutboundConnector;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public abstract class AbstractDnsUpstreamConnector extends AbstractOutboundConnector {

      private static final ConcurrentMap<PoolKey, ChannelFuture> POOL = new ConcurrentHashMap<>();



      protected record PoolKey(ProtocolType type, String host, int port) {}

      @Override
      public ChannelFuture connect(Channel inbound,
                                   ProxyTunnelRequest req,
                                   Promise<Channel> relayPromise) {
          RouteConfig routeConfig = req.getRouteConfig();
          String host = routeConfig.getOutboundProxyHost();
          int port = routeConfig.getOutboundProxyPort() != null ? routeConfig.getOutboundProxyPort() : defaultPort();
          PoolKey key = new PoolKey(outboundType(), host, port);

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

          if (pooled.isSuccess() && pooled.channel().isActive() && ready(pooled.channel())) {
              relayPromise.trySuccess(pooled.channel());
              return pooled;
          }

          pooled.addListener((ChannelFutureListener) f -> {
              if (f.isSuccess() && ready(f.channel())) {
                  relayPromise.trySuccess(f.channel());
              } else {
                  relayPromise.tryFailure(f.cause());
                  POOL.remove(key, f);
              }
          });
          return pooled;
      }

      protected abstract int defaultPort();           // 53 for UDP, 853 for DoT
      protected abstract ProtocolType outboundType(); // DNS_SERVER / DOT
      protected abstract boolean ready(Channel ch);   // DoT 要求 TLS 握手完成
      protected abstract ChannelFuture create(Channel inbound, String host, int port, PoolKey key);
  }