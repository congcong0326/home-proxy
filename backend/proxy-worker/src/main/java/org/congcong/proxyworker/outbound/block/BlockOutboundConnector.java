package org.congcong.proxyworker.outbound.block;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.concurrent.Promise;
import org.congcong.proxyworker.outbound.OutboundConnector;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

public class BlockOutboundConnector implements OutboundConnector {

      @Override
      public ChannelFuture connect(Channel inboundChannel,
                                   ProxyTunnelRequest request,
                                   Promise<Channel> relayPromise) {
          BlockedByPolicyException cause =
                  new BlockedByPolicyException(request.getTargetHost(), request.getTargetPort());
          // 让 TcpTunnelConnectorHandler 的失败分支生效，触发 onConnectFailure() 响应
          ChannelFuture failed = inboundChannel.newFailedFuture(cause);
          failed.addListener(f -> inboundChannel.close());
          return failed;
      }

      private static class BlockedByPolicyException extends Exception {
          BlockedByPolicyException(String host, int port) {
              super("blocked by route policy: " + host + ":" + port);
          }
      }
  }