package org.congcong.proxyworker.outbound.http;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.outbound.AbstractOutboundConnector;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

import java.util.Base64;

/**
 * 通过上游 HTTP CONNECT 代理转发的出站连接器。
 */
@Slf4j
public class HttpProxyOutboundConnector extends AbstractOutboundConnector {

    @Override
    public ChannelFuture connect(Channel inboundChannel, ProxyTunnelRequest request, Promise<Channel> relayPromise) {
        Bootstrap b = new Bootstrap();
        b.group(inboundChannel.eventLoop())
                .channel(getSocketChannel())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new HttpClientCodec(),
                                new HttpObjectAggregator(1024 * 8),
                                new HttpProxyClientHandler(request, relayPromise)
                        );
                    }
                });

        // 连接上游 HTTP 代理（使用路由配置中的最终地址）
        return b.connect(request.getFinalTargetHost(), request.getFinalTargetPort());
    }

    private static class HttpProxyClientHandler extends SimpleChannelInboundHandler<Object> {

        private final ProxyTunnelRequest proxyTunnelRequest;
        private final Promise<Channel> promise;

        private final boolean needAuth;
        private final String username;
        private final String password;

        public HttpProxyClientHandler(ProxyTunnelRequest proxyTunnelRequest, Promise<Channel> promise) {
            this.proxyTunnelRequest = proxyTunnelRequest;
            this.promise = promise;

            RouteConfig route = proxyTunnelRequest.getRouteConfig();
            String u = route != null ? route.getOutboundProxyUsername() : null;
            String p = route != null ? route.getOutboundProxyPassword() : null;
            this.needAuth = (u != null && p != null);
            this.username = u;
            this.password = p;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            // 构造 HTTP CONNECT 请求，目标为原始目的主机与端口
            String targetHost = proxyTunnelRequest.getTargetHost();
            int targetPort = proxyTunnelRequest.getTargetPort();
            String authority = targetHost + ":" + targetPort;

            DefaultFullHttpRequest req = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.CONNECT,
                    authority,
                    Unpooled.EMPTY_BUFFER
            );

            HttpHeaders headers = req.headers();
            headers.set(HttpHeaderNames.HOST, authority);
            headers.set("Proxy-Connection", "keep-alive");
            headers.set(HttpHeaderNames.CONNECTION, "keep-alive");
            if (needAuth) {
                String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
                headers.set(HttpHeaderNames.PROXY_AUTHORIZATION, "Basic " + token);
            }

            ctx.writeAndFlush(req);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof FullHttpResponse response) {
                handleResponse(ctx, response);
            } else if (msg instanceof HttpResponse response) {
                handleResponse(ctx, response);
            }
        }

        private void handleResponse(ChannelHandlerContext ctx, HttpResponse response) {
            int code = response.status().code();
            if (code == 200) {
                log.debug("http connect proxy established");
                // 清理 HTTP 编解码器，进入纯 TCP 隧道
                ChannelPipeline p = ctx.channel().pipeline();
                if (p.get(HttpClientCodec.class) != null) {
                    p.remove(HttpClientCodec.class);
                }
                if (p.get(HttpObjectAggregator.class) != null) {
                    p.remove(HttpObjectAggregator.class);
                }
                p.remove(this);
                promise.setSuccess(ctx.channel());
            } else {
                log.warn("http connect proxy failed, status={}", code);
                ctx.close();
                promise.setFailure(new RuntimeException("HTTP CONNECT failed: " + code));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
            promise.setFailure(cause);
        }
    }
}