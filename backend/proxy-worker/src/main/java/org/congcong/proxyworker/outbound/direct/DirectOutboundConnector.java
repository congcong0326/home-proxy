package org.congcong.proxyworker.outbound.direct;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.util.concurrent.Promise;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.proxyworker.outbound.AbstractOutboundConnector;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.congcong.proxyworker.security.DestinationAddressPolicy;
import org.congcong.proxyworker.security.PrivateDestinationBlockedException;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;

public class DirectOutboundConnector extends AbstractOutboundConnector {

    @Override
    public ChannelFuture connect(Channel inboundChannel, ProxyTunnelRequest request, Promise<Channel> relayPromise) {
        String targetHost = request.getFinalTargetHost();
        int targetPort = request.getFinalTargetPort();
        Optional<InetAddress> literal = DestinationAddressPolicy.parseLiteral(targetHost);
        Optional<InetAddress> unsafeLiteral = literal.filter(address -> !DestinationAddressPolicy.isPubliclyRoutable(address));
        if (requiresPublicAddressValidation(request) && unsafeLiteral.isPresent()) {
            return inboundChannel.newFailedFuture(
                    PrivateDestinationBlockedException.forLiteral(targetHost, targetPort, unsafeLiteral.orElseThrow()));
        }

        Bootstrap b = new Bootstrap();
        b.group(inboundChannel.eventLoop())
                .channel(getSocketChannel())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new DirectClientHandler(relayPromise));

        if (requiresPublicAddressValidation(request)) {
            b.resolver(PublicAddressResolverGroup.INSTANCE);
        }
        // A parsed literal is already safe to use as a resolved address; a
        // hostname stays unresolved so the validating resolver does exactly
        // one resolveAll call and Bootstrap connects to that returned IP.
        InetSocketAddress destination = literal
                .map(address -> new InetSocketAddress(address, targetPort))
                .orElseGet(() -> InetSocketAddress.createUnresolved(targetHost, targetPort));
        return b.connect(destination);
    }

    private boolean requiresPublicAddressValidation(ProxyTunnelRequest request) {
        return request.getRouteConfig() != null
                && request.getRouteConfig().getPolicy() == RoutePolicy.DIRECT
                && DestinationAddressPolicy.isProtectedInbound(request.getProtocolType());
    }


    private static class DirectClientHandler extends ChannelInboundHandlerAdapter {

        private final Promise<Channel> promise;

        public DirectClientHandler(Promise<Channel> promise) {
            this.promise = promise;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            promise.setSuccess(ctx.channel());
            ctx.pipeline().remove(this);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable throwable) {
            promise.setFailure(throwable);
        }
    }
}
