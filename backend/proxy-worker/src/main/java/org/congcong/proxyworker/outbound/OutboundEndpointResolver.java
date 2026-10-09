package org.congcong.proxyworker.outbound;

import com.google.common.net.InetAddresses;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.DefaultChannelPromise;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.util.concurrent.Promise;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.dns.DnsQueryContext;
import org.congcong.proxyworker.dns.DnsQueryService;
import org.congcong.proxyworker.dns.DnsResult;
import org.congcong.proxyworker.dns.FakeIpRegistry;
import org.congcong.proxyworker.dns.QueryMode;
import org.congcong.proxyworker.server.ProxyContext;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.congcong.proxyworker.security.ProxySafetyCounters;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Resolves configured proxy endpoints through the worker DNS view before TCP connect. */
public final class OutboundEndpointResolver {
    private OutboundEndpointResolver() {}

    public static ChannelFuture connect(Channel inbound, ProxyTunnelRequest request, Promise<Channel> relayPromise,
                                        String host, int port, Function<InetSocketAddress, ChannelFuture> connector) {
        if (host == null || host.isBlank() || port < 1 || port > 65_535) {
            return inbound.newFailedFuture(new IllegalArgumentException("invalid outbound endpoint"));
        }
        if (InetAddresses.isInetAddress(host)) {
            if (FakeIpRegistry.isInPool(host)) {
                ProxySafetyCounters.rejectedPoolAddress();
                return inbound.newFailedFuture(new IllegalArgumentException("outbound endpoint belongs to Fake IP pool"));
            }
            return connector.apply(new InetSocketAddress(InetAddresses.forString(host), port));
        }
        Optional<InboundConfig> dns = request.isDnsInboundConfigCaptured()
                ? Optional.ofNullable(request.getDnsInboundConfig())
                : ProxyContext.getInstance().getEnabledDnsInbound();
        if (dns.isEmpty()) {
            return connector.apply(InetSocketAddress.createUnresolved(host, port));
        }
        String clientIp = request.getProxyContext() == null ? null : request.getProxyContext().getClientIp();
        if (clientIp == null && inbound.remoteAddress() instanceof InetSocketAddress peer
                && peer.getAddress() != null) clientIp = peer.getAddress().getHostAddress();
        DnsQueryContext context = new DnsQueryContext(clientIp, dns.get(), dns.get().getConfigVersion());
        DeferredFuture overall = new DeferredFuture(inbound);
        CompletableFuture<DnsResult> lookup = queryAddresses(host, context, inbound);
        ChannelFutureListener cancelOnClose = closed -> {
            lookup.cancel(false);
            overall.tryFailure(new IllegalStateException("inbound closed during proxy endpoint lookup"));
        };
        inbound.closeFuture().addListener(cancelOnClose);
        overall.addListener(done -> inbound.closeFuture().removeListener(cancelOnClose));
        lookup.whenComplete((result, error) -> inbound.eventLoop().execute(() -> {
            if (overall.isDone()) return;
            if (result != null && request.getProxyContext() != null) {
                request.getProxyContext().setDnsRouteId(result.routeId());
            }
            if (error != null || result.code() != DnsResponseCode.NOERROR || result.addresses().isEmpty()) {
                Throwable failure = error != null ? error : new IllegalStateException("proxy endpoint DNS resolution failed");
                relayPromise.tryFailure(failure);
                overall.tryFailure(failure);
                return;
            }
            try {
                String address = result.addresses().get(0);
                if (FakeIpRegistry.isInPool(address)) {
                    ProxySafetyCounters.rejectedPoolAddress();
                    throw new IllegalStateException("proxy endpoint resolved to Fake IP");
                }
                InetSocketAddress endpoint = new InetSocketAddress(InetAddresses.forString(address), port);
                ChannelFuture connection = connector.apply(endpoint);
                overall.setChannel(connection.channel());
                connection.addListener((ChannelFutureListener) done -> {
                    if (done.isSuccess()) overall.trySuccess(null);
                    else overall.tryFailure(done.cause());
                });
            } catch (RuntimeException failure) {
                relayPromise.tryFailure(failure);
                overall.tryFailure(failure);
            }
        }));
        return overall;
    }

    private static CompletableFuture<DnsResult> queryAddresses(String host, DnsQueryContext context, Channel inbound) {
        DnsQueryService service = DnsQueryService.getInstance();
        CompletableFuture<DnsResult> lookup = new CompletableFuture<>();
        CompletableFuture<DnsResult> first = service.query(host, DnsRecordType.A, QueryMode.OUTBOUND_REAL,
                context, inbound.eventLoop());
        AtomicReference<CompletableFuture<DnsResult>> active = new AtomicReference<>(first);
        lookup.whenComplete((ignored, failure) -> {
            if (lookup.isCancelled()) active.get().cancel(false);
        });
        first.whenComplete((answer, failure) -> {
            if (lookup.isCancelled()) return;
            if (failure != null) {
                lookup.completeExceptionally(failure);
            } else if (answer.code() == DnsResponseCode.NOERROR && answer.addresses().isEmpty()) {
                CompletableFuture<DnsResult> next = service.query(host, DnsRecordType.AAAA, QueryMode.OUTBOUND_REAL,
                        context, inbound.eventLoop());
                active.set(next);
                if (lookup.isCancelled()) next.cancel(false);
                else next.whenComplete((retried, retryFailure) -> {
                    if (retryFailure == null) lookup.complete(retried);
                    else lookup.completeExceptionally(retryFailure);
                });
            } else {
                lookup.complete(answer);
            }
        });
        return lookup;
    }

    private static final class DeferredFuture extends DefaultChannelPromise {
        private volatile Channel destinationChannel;
        private DeferredFuture(Channel inbound) {
            super(inbound, inbound.eventLoop());
            destinationChannel = inbound;
        }
        private void setChannel(Channel channel) { destinationChannel = channel; }
        @Override public Channel channel() { return destinationChannel; }
    }
}
