package org.congcong.proxyworker.protocol.http;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.congcong.proxyworker.protocol.ProtocolStrategy;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

/**
 * HTTPS CONNECT 协议策略：在出站连接成功后，回写 200 响应并进入中继。
 */
public class HttpsConnectProtocolStrategy implements ProtocolStrategy {

    @Override
    public void onConnectSuccess(ChannelHandlerContext inboundChannelContext, Channel outboundChannel, ProxyTunnelRequest request) {
        String connect = "HTTP/1.1 200 Connection Established\r\n" +
                "Proxy-agent: https://github.com/cong/cong\r\n" +
                "\r\n";
        inboundChannelContext.writeAndFlush(inboundChannelContext.alloc().buffer().writeBytes(connect.getBytes()));
    }

    @Override
    public void onConnectFailure(ChannelHandlerContext inboundChannelContext, Channel outboundChannel, ProxyTunnelRequest request, Throwable cause) {
        String resp = "HTTP/1.1 502 Bad Gateway\r\n" +
                "Connection: close\r\n" +
                "Content-Length: 0\r\n\r\n";
        inboundChannelContext.writeAndFlush(inboundChannelContext.alloc().buffer().writeBytes(resp.getBytes()));
    }
}