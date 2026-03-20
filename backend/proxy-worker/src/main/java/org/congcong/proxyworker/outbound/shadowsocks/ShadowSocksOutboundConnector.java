package org.congcong.proxyworker.outbound.shadowsocks;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.enums.ProxyEncAlgo;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.outbound.AbstractOutboundConnector;
import org.congcong.proxyworker.protocol.shadowsock.EncryptedSocksHandler;
import org.congcong.proxyworker.protocol.shadowsock.DecryptedSocksHandler;
import org.congcong.proxyworker.protocol.shadowsock.ShadowSocks2022ClientChunkEncoder;
import org.congcong.proxyworker.protocol.shadowsock.ShadowSocks2022ClientHandshakeHandler;
import org.congcong.proxyworker.protocol.shadowsock.ShadowSocks2022ClientResponseDecoder;
import org.congcong.proxyworker.protocol.shadowsock.ShadowSocks2022ClientSession;
import org.congcong.proxyworker.protocol.shadowsock.ShadowSocks2022Support;
import org.congcong.proxyworker.protocol.shadowsock.ShadowSocksAddressCodec;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.congcong.proxyworker.util.encryption.CryptoProcessorFactory;

@Slf4j
public class ShadowSocksOutboundConnector extends AbstractOutboundConnector {

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
                .channel(getSocketChannel())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) throws Exception {
                        if (ShadowSocks2022Support.isEnabled(outboundProxyEncAlgo)) {
                            ShadowSocks2022ClientSession session = new ShadowSocks2022ClientSession();
                            var requestCryptoProcessor = CryptoProcessorFactory.createProcessor(outboundProxyEncAlgo, outboundProxyPassword);
                            var responseCryptoProcessor = CryptoProcessorFactory.createProcessor(outboundProxyEncAlgo, outboundProxyPassword);
                            socketChannel.pipeline().addLast(
                                    new ShadowSocks2022ClientResponseDecoder(
                                            responseCryptoProcessor,
                                            session
                                    ),
                                    new ShadowSocks2022ClientHandshakeHandler(
                                            request,
                                            relayPromise,
                                            requestCryptoProcessor,
                                            session,
                                            outboundProxyPassword
                                    ),
                                    new ShadowSocks2022ClientChunkEncoder(
                                            requestCryptoProcessor
                                    )
                            );
                            return;
                        }
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
            ShadowSocksAddressCodec.writeAddress(requestBuf, targetHost, targetPort);


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
