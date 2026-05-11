package org.congcong.proxyworker.outbound.reality;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.concurrent.Promise;
import org.congcong.proxyworker.outbound.AbstractOutboundConnector;
import org.congcong.proxyworker.outbound.reality.config.RealityClientConfig;
import org.congcong.proxyworker.outbound.reality.config.VlessRealityOutboundConfig;
import org.congcong.proxyworker.outbound.reality.session.RealityHandshakeHandler;
import org.congcong.proxyworker.outbound.reality.session.VlessRealityOutboundHandler;
import org.congcong.proxyworker.outbound.reality.tls.RealityClientHelloFactory;
import org.congcong.proxyworker.outbound.reality.tls.RealityHandshakeEngine;
import org.congcong.proxyworker.outbound.reality.tls.TlsRecordDecoder;
import org.congcong.proxyworker.outbound.reality.tls.TlsRecordEncoder;
import org.congcong.proxyworker.outbound.reality.trace.ConnectionTrace;
import org.congcong.proxyworker.outbound.reality.vless.VlessRequest;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

public class VlessRealityOutboundConnector extends AbstractOutboundConnector {

    @Override
    public ChannelFuture connect(Channel inboundChannel, ProxyTunnelRequest request, Promise<Channel> relayPromise) {
        final VlessRealityOutboundConfig outboundConfig;
        try {
            outboundConfig = VlessRealityOutboundConfig.from(request.getRouteConfig());
        } catch (RuntimeException e) {
            return inboundChannel.newFailedFuture(e);
        }

        RealityClientConfig realityConfig = outboundConfig.toRealityClientConfig();
        VlessRequest vlessRequest = new VlessRequest(
                outboundConfig.uuid(),
                request.getTargetHost(),
                request.getTargetPort(),
                outboundConfig.flow());
        ConnectionTrace trace = new ConnectionTrace();

        Bootstrap bootstrap = new Bootstrap()
                .group(inboundChannel.eventLoop())
                .channel(getSocketChannel())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, outboundConfig.connectTimeoutMillis())
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        RealityHandshakeEngine handshakeEngine = new RealityHandshakeEngine(
                                realityConfig,
                                new RealityClientHelloFactory());
                        ch.pipeline().addLast(new TlsRecordEncoder());
                        ch.pipeline().addLast(new TlsRecordDecoder());
                        ch.pipeline().addLast(new RealityHandshakeHandler(handshakeEngine, trace));
                        ch.pipeline().addLast(new VlessRealityOutboundHandler(
                                vlessRequest,
                                trace,
                                handshakeEngine,
                                handshakeEngine,
                                relayPromise));
                    }
                });

        return bootstrap.connect(outboundConfig.host(), outboundConfig.port());
    }
}
