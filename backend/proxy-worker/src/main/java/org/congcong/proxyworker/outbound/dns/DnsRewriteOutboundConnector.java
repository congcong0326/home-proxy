package org.congcong.proxyworker.outbound.dns;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.concurrent.Promise;
import org.congcong.proxyworker.outbound.OutboundConnector;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

public class DnsRewriteOutboundConnector implements OutboundConnector {
      @Override
      public ChannelFuture connect(Channel inboundChannel,
                                   ProxyTunnelRequest request,
                                   Promise<Channel> relayPromise) {
          // 不建立任何外联，直接触发 promise 成功，传个占位 channel 即可
          ChannelFuture success = inboundChannel.newSucceededFuture();
          relayPromise.trySuccess(inboundChannel);
          return success;
      }
  }