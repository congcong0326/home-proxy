package org.congcong.proxyworker.outbound.shadowsocks;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.enums.ProxyEncAlgo;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.outbound.OutboundConnector;
import org.congcong.proxyworker.protocol.shadowsock.EncryptedSocksHandler;
import org.congcong.proxyworker.protocol.shadowsock.DecryptedSocksHandler;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.congcong.proxyworker.util.encryption.CryptoProcessorFactory;
import org.congcong.proxyworker.util.encryption.algorithm.CryptoProcessor;

@Slf4j
public class ShadowSocksOutboundConnector implements OutboundConnector {

    @Override
    public ChannelFuture connect(Channel inboundChannel, ProxyTunnelRequest request, Promise<Channel> relayPromise) {
        RouteConfig routeConfig = request.getRouteConfig();
        // 密钥
        String outboundProxyPassword = routeConfig.getOutboundProxyPassword();
        // 采用的加密算法
        ProxyEncAlgo outboundProxyEncAlgo = routeConfig.getOutboundProxyEncAlgo();
        // 目标ss服务器host
        String outboundProxyHost = routeConfig.getOutboundProxyHost();
        // 目标ss服务器port
        Integer outboundProxyPort = routeConfig.getOutboundProxyPort();

        Bootstrap b = new Bootstrap();
        b.group(inboundChannel.eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) throws Exception {
                        socketChannel.pipeline().addLast(
                                // 加密出站数据
                                new EncryptedSocksHandler(CryptoProcessorFactory.createProcessor(outboundProxyEncAlgo, outboundProxyPassword)),
                                // 解密入站数据
                                new DecryptedSocksHandler(CryptoProcessorFactory.createProcessor(outboundProxyEncAlgo, outboundProxyPassword)),
                                // ShadowSocks客户端处理器
                                new ShadowSocksClientHandler(request, relayPromise)
                        );
                    }
                });

        return b.connect(outboundProxyHost, outboundProxyPort);
    }

    private static class ShadowSocksClientHandler extends SimpleChannelInboundHandler<ByteBuf> {

        private final ProxyTunnelRequest proxyTunnelRequest;
        private final Promise<Channel> promise;
        private boolean handshakeComplete = false;

        public ShadowSocksClientHandler(ProxyTunnelRequest proxyTunnelRequest, Promise<Channel> promise) {
            this.proxyTunnelRequest = proxyTunnelRequest;
            this.promise = promise;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            // 发送ShadowSocks请求头：[1-byte type][variable-length host][2-byte port]
            sendShadowSocksRequest(ctx);
        }

        private void sendShadowSocksRequest(ChannelHandlerContext ctx) {
            String targetHost = proxyTunnelRequest.getTargetHost();
            int targetPort = proxyTunnelRequest.getTargetPort();

            ByteBuf requestBuf = ctx.alloc().buffer();
            
            // 确定地址类型并写入
            if (NetUtil.isValidIpV4Address(targetHost)) {
                requestBuf.writeByte(Socks5AddressType.IPv4.byteValue());
                String[] parts = targetHost.split("\\.");
                for (String part : parts) {
                    requestBuf.writeByte(Integer.parseInt(part));
                }
            } else if (NetUtil.isValidIpV6Address(targetHost)) {
                requestBuf.writeByte(Socks5AddressType.IPv6.byteValue());
                // IPv6地址处理
                String[] parts = targetHost.split(":");
                for (String part : parts) {
                    if (part.isEmpty()) {
                        requestBuf.writeShort(0);
                    } else {
                        requestBuf.writeShort(Integer.parseInt(part, 16));
                    }
                }
            } else {
                // 域名
                requestBuf.writeByte(Socks5AddressType.DOMAIN.byteValue());
                byte[] hostBytes = targetHost.getBytes();
                requestBuf.writeByte(hostBytes.length);
                requestBuf.writeBytes(hostBytes);
            }
            
            // 写入端口
            requestBuf.writeShort(targetPort);
            


            ctx.writeAndFlush(requestBuf).addListener(future -> {
                if (future.isSuccess()) {
                    log.debug("ShadowSocks request sent successfully to {}:{}", targetHost, targetPort);
                    // ShadowSocks协议没有握手响应，直接标记为完成
                    handshakeComplete = true;
                    // 移除自己，准备数据转发
                    ctx.pipeline().remove(this);
                    promise.setSuccess(ctx.channel());
                } else {
                    log.error("Failed to send ShadowSocks request", future.cause());
                    ctx.close();
                    promise.setFailure(future.cause());
                }
            });
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
            // ShadowSocks协议在发送请求后没有握手响应，所有接收到的数据都是目标服务器的响应
            // 这里不应该接收到数据，如果接收到说明可能有问题
            if (!handshakeComplete) {
                log.warn("Received unexpected data before handshake complete");
                ctx.close();
                promise.setFailure(new RuntimeException("Unexpected data received"));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("ShadowSocks client handler exception", cause);
            ctx.close();
            if (!promise.isDone()) {
                promise.setFailure(cause);
            }
        }
    }
}
