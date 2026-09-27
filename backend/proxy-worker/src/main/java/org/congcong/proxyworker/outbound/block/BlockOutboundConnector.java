package org.congcong.proxyworker.outbound.block;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.concurrent.Promise;
import org.congcong.proxyworker.outbound.OutboundConnector;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

public class BlockOutboundConnector implements OutboundConnector {

      private final Throwable cause;

      public BlockOutboundConnector() {
          this.cause = null;
      }

      public BlockOutboundConnector(Throwable cause) {
          this.cause = cause;
      }

      @Override
      public ChannelFuture connect(Channel inboundChannel,
                                   ProxyTunnelRequest request,
                                   Promise<Channel> relayPromise) {
          Throwable failure = cause == null
                  ? new BlockedByPolicyException(request.getTargetHost(), request.getTargetPort())
                  : cause;
          // 让 TcpTunnelConnectorHandler 的失败分支生效，触发 onConnectFailure() 响应
          return inboundChannel.newFailedFuture(failure);
      }

      public static class BlockedByPolicyException extends Exception {
          BlockedByPolicyException(String host, int port) {
              super("blocked by route policy: " + host + ":" + port);
          }
      }
  }
