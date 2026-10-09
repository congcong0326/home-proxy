package org.congcong.proxyworker.outbound.direct;

import com.google.common.net.InetAddresses;
import io.netty.bootstrap.Bootstrap;
import org.congcong.proxyworker.telemetry.TelemetryHooks;
import org.congcong.proxyworker.telemetry.SessionObservation.Stage;
import io.netty.channel.*;
import io.netty.util.concurrent.Promise;
import io.netty.handler.codec.dns.DnsRecordType;
import org.congcong.proxyworker.dns.DnsQueryContext;
import org.congcong.proxyworker.dns.DnsQueryService;
import org.congcong.proxyworker.dns.DnsResult;
import org.congcong.proxyworker.dns.FakeIpRegistry;
import org.congcong.proxyworker.dns.QueryMode;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.proxyworker.outbound.AbstractOutboundConnector;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.congcong.proxyworker.security.DestinationAddressPolicy;
import org.congcong.proxyworker.security.PrivateDestinationBlockedException;
import org.congcong.proxyworker.security.ProxySafetyCounters;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class DirectOutboundConnector extends AbstractOutboundConnector {

    @Override
    public ChannelFuture connect(Channel inboundChannel, ProxyTunnelRequest request, Promise<Channel> relayPromise) {
        String targetHost = request.getFinalTargetHost();
        int targetPort = request.getFinalTargetPort();
        Optional<InetAddress> literal = DestinationAddressPolicy.parseLiteral(targetHost);
        if (literal.filter(address -> FakeIpRegistry.isInPool(address.getHostAddress())).isPresent()) {
            ProxySafetyCounters.rejectedPoolAddress();
            return inboundChannel.newFailedFuture(new IllegalArgumentException("direct destination belongs to Fake IP pool"));
        }
        Optional<InetAddress> unsafeLiteral = literal.filter(address -> !DestinationAddressPolicy.isPubliclyRoutable(address));
        if (requiresPublicAddressValidation(request) && unsafeLiteral.isPresent()) {
            ProxySafetyCounters.rejectedDestinationAddress();
            return inboundChannel.newFailedFuture(
                    PrivateDestinationBlockedException.forLiteral(targetHost, targetPort, unsafeLiteral.orElseThrow()));
        }

        if (literal.isEmpty()) {
            Optional<InboundConfig> dns = request.isDnsInboundConfigCaptured()
                    ? Optional.ofNullable(request.getDnsInboundConfig())
                    : org.congcong.proxyworker.server.ProxyContext.getInstance().getEnabledDnsInbound();
            if (dns.isPresent()) {
                return resolveAndConnect(inboundChannel, request, relayPromise, targetHost, targetPort, dns.get());
            }
        }

        InetSocketAddress destination = literal
                .map(address -> new InetSocketAddress(address, targetPort))
                .orElseGet(() -> InetSocketAddress.createUnresolved(targetHost, targetPort));
        return connectTcp(inboundChannel, request, relayPromise, destination);
    }

    private ChannelFuture resolveAndConnect(Channel inbound, ProxyTunnelRequest request, Promise<Channel> relayPromise,
                                            String host, int port, InboundConfig dnsInbound) {
        DeferredConnectFuture overall = new DeferredConnectFuture(inbound);
        String clientIp = request.getProxyContext() != null ? request.getProxyContext().getClientIp() : null;
        if (clientIp == null && inbound.remoteAddress() instanceof InetSocketAddress peer
                && peer.getAddress() != null) clientIp = peer.getAddress().getHostAddress();
        DnsQueryContext context = new DnsQueryContext(clientIp, dnsInbound, dnsInbound.getConfigVersion());
        TelemetryHooks.beginStage(inbound, Stage.DNS);
        CompletableFuture<DnsResult> dns = queryAddresses(host, context, inbound);
        inbound.closeFuture().addListener(closed -> {
            dns.cancel(false);
            overall.tryFailure(new IllegalStateException("inbound closed during DNS lookup"));
        });
        dns.whenComplete((answer, error) -> inbound.eventLoop().execute(() -> {
            TelemetryHooks.endStage(inbound, Stage.DNS);
            if (overall.isDone()) return;
            if (error != null) {
                relayPromise.tryFailure(error);
                overall.tryFailure(error);
                return;
            }
            try {
                if (request.getProxyContext() != null) request.getProxyContext().setDnsRouteId(answer.routeId());
                if (answer.code() != io.netty.handler.codec.dns.DnsResponseCode.NOERROR
                        || answer.addresses().isEmpty()) {
                    throw new IllegalStateException("DNS route blocked or returned no address for " + host);
                }
                List<InetSocketAddress> addresses = answer.addresses().stream()
                        .map(address -> new InetSocketAddress(InetAddresses.forString(address), port)).toList();
                for (InetSocketAddress address : addresses) {
                    if (FakeIpRegistry.isInPool(address.getAddress().getHostAddress())) {
                        ProxySafetyCounters.rejectedPoolAddress();
                        throw new IllegalStateException("DNS result belongs to Fake IP pool");
                    }
                }
                if (requiresPublicAddressValidation(request) && addresses.stream()
                        .anyMatch(address -> !DestinationAddressPolicy.isPubliclyRoutable(address.getAddress()))) {
                    ProxySafetyCounters.rejectedDestinationAddress();
                    throw PrivateDestinationBlockedException.forResolved(host, port, addresses);
                }
                ChannelFuture connected = connectTcp(inbound, request, relayPromise, addresses.get(0));
                request.setResolvedTargetIp(addresses.get(0).getAddress().getHostAddress());
                overall.setChannel(connected.channel());
                connected.addListener((ChannelFutureListener) future -> {
                    if (future.isSuccess()) overall.trySuccess(null);
                    else overall.tryFailure(future.cause());
                });
            } catch (Exception failure) {
                relayPromise.tryFailure(failure);
                overall.tryFailure(failure);
            }
        }));
        return overall;
    }

    private CompletableFuture<DnsResult> queryAddresses(String host, DnsQueryContext context, Channel inbound) {
        DnsQueryService service = DnsQueryService.getInstance();
        CompletableFuture<DnsResult> result = new CompletableFuture<>();
        CompletableFuture<DnsResult> first = service.query(host, DnsRecordType.A, QueryMode.OUTBOUND_REAL,
                context, inbound.eventLoop());
        AtomicReference<CompletableFuture<DnsResult>> active = new AtomicReference<>(first);
        // Cancelling a thenCompose stage does not cancel its source. Propagate inbound closure
        // to the active subscription so abandoned DNS work cannot populate the answer cache.
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) active.get().cancel(false);
        });
        first.whenComplete((answer, failure) -> {
            if (result.isCancelled()) return;
            if (failure != null) {
                result.completeExceptionally(failure);
            } else if (answer.code() == io.netty.handler.codec.dns.DnsResponseCode.NOERROR
                    && answer.addresses().isEmpty()) {
                CompletableFuture<DnsResult> next = service.query(host, DnsRecordType.AAAA,
                        QueryMode.OUTBOUND_REAL, context, inbound.eventLoop());
                active.set(next);
                if (result.isCancelled()) next.cancel(false);
                else next.whenComplete((retried, retryFailure) -> {
                    if (retryFailure == null) result.complete(retried);
                    else result.completeExceptionally(retryFailure);
                });
            } else {
                result.complete(answer);
            }
        });
        return result;
    }

    private ChannelFuture connectTcp(Channel inboundChannel, ProxyTunnelRequest request,
                                     Promise<Channel> relayPromise, InetSocketAddress destination) {

        Bootstrap b = new Bootstrap();
        b.group(inboundChannel.eventLoop())
                .channel(getSocketChannel())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<Channel>() {
                    @Override protected void initChannel(Channel channel) {
                        channel.pipeline().addLast(new DirectClientHandler(relayPromise));
                        TelemetryHooks.outbound(channel, inboundChannel, false, false, null);
                    }
                });

        if (requiresPublicAddressValidation(request) && destination.isUnresolved()) {
            b.resolver(PublicAddressResolverGroup.INSTANCE);
        }
        return TelemetryHooks.connectTcp(b, inboundChannel, destination);
    }

    private static final class DeferredConnectFuture extends DefaultChannelPromise {
        private volatile Channel destinationChannel;

        private DeferredConnectFuture(Channel inbound) {
            super(inbound, inbound.eventLoop());
            destinationChannel = inbound;
        }

        private void setChannel(Channel channel) { destinationChannel = channel; }

        @Override public Channel channel() { return destinationChannel; }
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
            promise.trySuccess(ctx.channel());
            ctx.pipeline().remove(this);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable throwable) {
            promise.tryFailure(throwable);
        }
    }
}
