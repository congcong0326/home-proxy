package org.congcong.proxyworker.outbound;

import io.netty.channel.Channel;
import io.netty.util.concurrent.Promise;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

/**
 * 出站连接器：封装直连或经上游代理/SS 的连接过程。
 */
public interface OutboundConnector {
    void connect(Channel inboundChannel, ProxyTunnelRequest request, Promise<Channel> relayPromise);
}