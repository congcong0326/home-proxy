package org.congcong.proxyworker.outbound.socks;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.handler.proxy.ProxyConnectException;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.outbound.AbstractOutboundConnector;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

@Slf4j
public class Socks5OutboundConnector extends AbstractOutboundConnector {



    @Override
    public ChannelFuture connect(Channel inboundChannel, ProxyTunnelRequest request, Promise<Channel> relayPromise) {
        Bootstrap b = new Bootstrap();
        b.group(inboundChannel.eventLoop())
                .channel(getSocketChannel())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) throws Exception {
                        socketChannel.pipeline().addLast(
                                Socks5ClientEncoder.DEFAULT,//编码
                                new Socks5InitialResponseDecoder(),//解码
                                new Socks5ClientHandler(request, relayPromise)//socks 客户端
                        );
                    }
                });

        return b.connect(request.getFinalTargetHost(), request.getFinalTargetPort());
    }

    private static class Socks5ClientHandler extends SimpleChannelInboundHandler<Object> {

        private final ProxyTunnelRequest proxyTunnelRequest;

        private final Promise<Channel> promise;

        private final boolean needAuth;
        private final String username;
        private final String password;

        public Socks5ClientHandler(ProxyTunnelRequest proxyTunnelRequest, Promise<Channel> promise) {
            this.proxyTunnelRequest = proxyTunnelRequest;
            this.promise = promise;

            RouteConfig routeConfig = proxyTunnelRequest.getRouteConfig();
            String outboundProxyUsername = routeConfig.getOutboundProxyUsername();
            String outboundProxyPassword = routeConfig.getOutboundProxyPassword();
            needAuth = outboundProxyUsername != null && outboundProxyPassword != null;
            this.username = outboundProxyUsername;
            this.password = outboundProxyPassword;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            if (needAuth) {
                // 提供 PASSWORD 与 NO_AUTH 两种，优先由服务器选择
                ctx.writeAndFlush(new DefaultSocks5InitialRequest(Socks5AuthMethod.NO_AUTH, Socks5AuthMethod.PASSWORD));
            } else {
                // 仅提供无认证
                ctx.writeAndFlush(new DefaultSocks5InitialRequest(Socks5AuthMethod.NO_AUTH));
            }
        }

        private void sendConnectRequest(ChannelHandlerContext ctx) throws ProxyConnectException {
            // 构建连接请求（目标地址和端口）
            // 注意：CONNECT 的目标应为原始目的主机/端口，而不是上游代理地址
            // 让代理服务器用IP直连，少走一次DNS解析
            String host = proxyTunnelRequest.getFinalTargetHost();
            Socks5AddressType addrType = null;
            if (NetUtil.isValidIpV4Address(host)) {
                addrType = Socks5AddressType.IPv4;
            } else if (NetUtil.isValidIpV6Address(host)) {
                addrType = Socks5AddressType.IPv6;
            } else {
                addrType = Socks5AddressType.DOMAIN;
            }
            Socks5CommandRequest request = new DefaultSocks5CommandRequest(
                    Socks5CommandType.CONNECT,
                    addrType,
                    host,  // 原始目标主机
                    proxyTunnelRequest.getTargetPort() // 原始目标端口
            );
            ctx.writeAndFlush(request);
        }



        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
            // 处理握手响应
            if (msg instanceof DefaultSocks5InitialResponse) {
                Socks5InitialResponse response = (Socks5InitialResponse) msg;
                Socks5AuthMethod selected = response.authMethod();
                if (selected == Socks5AuthMethod.NO_AUTH) {
                    // 无认证，直接发送连接请求
                    ctx.pipeline().addFirst(new Socks5CommandResponseDecoder());
                    ctx.pipeline().remove(Socks5InitialResponseDecoder.class);
                    sendConnectRequest(ctx);
                } else if (selected == Socks5AuthMethod.PASSWORD) {
                    // 发送用户名密码认证请求
                    ctx.pipeline().addFirst(new Socks5PasswordAuthResponseDecoder());
                    ctx.pipeline().remove(Socks5InitialResponseDecoder.class);
                    ctx.writeAndFlush(new DefaultSocks5PasswordAuthRequest(username, password));
                } else {
                    ctx.close();
                    promise.setFailure(new RuntimeException("unsupported auth method: " + selected));
                }
            } else if (msg instanceof Socks5PasswordAuthResponse authResp) {
                if (authResp.status() == Socks5PasswordAuthStatus.SUCCESS) {
                    // 认证通过，继续 CONNECT
                    ctx.pipeline().addFirst(new Socks5CommandResponseDecoder());
                    ctx.pipeline().remove(Socks5PasswordAuthResponseDecoder.class);
                    sendConnectRequest(ctx);
                } else {
                    log.warn("password auth failed: {}", authResp.status());
                    ctx.close();
                    promise.setFailure(new RuntimeException("password auth failed"));
                }
            } else if (msg instanceof Socks5CommandResponse response) {
                if (response.status() == Socks5CommandStatus.SUCCESS) {
                    log.debug("socks5 connect success");
                    ctx.channel().pipeline().remove(Socks5ClientEncoder.class);
                    ctx.channel().pipeline().remove(Socks5CommandResponseDecoder.class);
                    ctx.channel().pipeline().remove(this);
                    promise.setSuccess(ctx.channel());
                } else {
                    log.warn("socks5 connect failed {}", response);
                    ctx.close();
                    promise.setFailure(new RuntimeException("connect failed"));
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
            promise.setFailure(new RuntimeException(cause.getMessage()));
        }
    }

}
