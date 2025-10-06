package org.congcong.proxyworker.outbound.direct;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Promise;
import org.congcong.proxyworker.outbound.OutboundConnector;
import org.congcong.proxyworker.protocol.DirectClientHandler;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

public class DirectOutboundConnector implements OutboundConnector {

    @Override
    public void connect(Channel inboundChannel, ProxyTunnelRequest request, Promise<Channel> relayPromise) {
        Bootstrap b = new Bootstrap();
        b.group(inboundChannel.eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new DirectClientHandler(relayPromise));

        b.connect(request.getFinalTargetHost(), request.getFinalTargetPort())
                .addListener((ChannelFutureListener) future -> {
                    if (!future.isSuccess()) {
                        // 统一失败处理，交由 TcpTunnelConnectorHandler 的 promise listener 写回协议层响应
                        relayPromise.setFailure(future.cause());
                        // 确保释放出站资源
                        Channel ch = future.channel();
                        if (ch != null && ch.isOpen()) {
                            ch.close();
                        }
                    }
                });
    }
}
