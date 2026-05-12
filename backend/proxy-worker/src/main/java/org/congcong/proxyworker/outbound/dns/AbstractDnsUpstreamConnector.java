package org.congcong.proxyworker.outbound.dns;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.DefaultChannelPromise;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.outbound.AbstractOutboundConnector;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public abstract class AbstractDnsUpstreamConnector extends AbstractOutboundConnector {

    private static final ConcurrentMap<PoolKey, ChannelFuture> POOL = new ConcurrentHashMap<>();
    private static final ConcurrentMap<PoolKey, UpstreamState> STATE = new ConcurrentHashMap<>();
    private static final AtomicInteger ROUND_ROBIN = new AtomicInteger();
    private static final int FAILURE_THRESHOLD = 2;
    private static final long COOLDOWN_MILLIS = 30_000L;

    protected record PoolKey(ProtocolType type, String host, int port) {
    }

    @Override
    public ChannelFuture connect(Channel inbound,
                                 ProxyTunnelRequest req,
                                 Promise<Channel> relayPromise) {
        RouteConfig routeConfig = req.getRouteConfig();
        List<String> hosts = parseHosts(routeConfig.getOutboundProxyHost());
        if (hosts.isEmpty()) {
            IllegalArgumentException cause = new IllegalArgumentException("No outbound DNS host configured");
            ChannelFuture failed = inbound.newFailedFuture(cause);
            relayPromise.tryFailure(cause);
            return failed;
        }

        int port = routeConfig.getOutboundProxyPort() != null ? routeConfig.getOutboundProxyPort() : defaultPort();
        ProtocolType type = outboundType();
        List<String> orderedHosts = orderHosts(hosts);
        long now = System.currentTimeMillis();
        List<String> candidates = pickAvailableHosts(orderedHosts, port, type, now);
        boolean allCooling = candidates.isEmpty();
        if (allCooling) {
            candidates = orderedHosts;
        }

        ChainedChannelFuture overall = new ChainedChannelFuture(inbound);
        attemptConnect(0, candidates, allCooling, inbound, relayPromise, overall, port, type, null);
        return overall;
    }

    protected abstract int defaultPort();           // 53 for UDP, 853 for DoT
    protected abstract ProtocolType outboundType(); // DNS_SERVER / DOT
    protected abstract boolean ready(Channel ch);   // DoT 要求 TLS 握手完成
    protected abstract ChannelFuture create(Channel inbound, String host, int port, PoolKey key);

    private void attemptConnect(int index,
                                List<String> candidates,
                                boolean allowCoolingFallback,
                                Channel inbound,
                                Promise<Channel> relayPromise,
                                ChainedChannelFuture overallFuture,
                                int port,
                                ProtocolType type,
                                Throwable lastCause) {
        if (overallFuture.isDone()) {
            return;
        }
        if (index >= candidates.size()) {
            Throwable cause = lastCause != null ? lastCause : new IllegalStateException("No available DNS upstream");
            relayPromise.tryFailure(cause);
            overallFuture.tryFailure(cause);
            return;
        }

        String host = candidates.get(index);
        String outboundIp = probeOutboundIp(host, port);
        PoolKey key = new PoolKey(type, host, port);
        ChannelFuture cf = POOL.compute(key, (k, existing) -> {
            if (!isChannelStale(existing, outboundIp)) {
                return existing;
            }
            if (existing != null) {
                closeChannelQuietly(existing.channel());
            }
            ChannelFuture created = create(inbound, host, port, k);
            created.addListener((ChannelFutureListener) f -> {
                if (f.isSuccess()) {
                    f.channel().closeFuture().addListener(close -> POOL.remove(k, f));
                } else {
                    POOL.remove(k, f);
                }
            });
            return created;
        });
        UpstreamState state = STATE.computeIfAbsent(key, k -> new UpstreamState());
        long now = System.currentTimeMillis();
        if (!allowCoolingFallback && state.inCooldown(now)) {
            attemptConnect(index + 1, candidates, allowCoolingFallback, inbound, relayPromise, overallFuture, port, type, lastCause);
            return;
        }

        if (cf.isDone()) {
            handleOutcome(cf, key, state, index, candidates, allowCoolingFallback, inbound, relayPromise, overallFuture, port, outboundIp);
        } else {
            cf.addListener((ChannelFutureListener) f ->
                    handleOutcome(f, key, state, index, candidates, allowCoolingFallback, inbound, relayPromise, overallFuture, port, outboundIp));
        }
    }

    private void handleOutcome(ChannelFuture future,
                               PoolKey key,
                               UpstreamState state,
                               int index,
                               List<String> candidates,
                               boolean allowCoolingFallback,
                               Channel inbound,
                               Promise<Channel> relayPromise,
                               ChainedChannelFuture overallFuture,
                               int port,
                               String outboundIp) {
        if (overallFuture.isDone()) {
            return;
        }
        if (!future.isSuccess() || !future.channel().isActive()) {
            Throwable cause = future.cause() != null ? future.cause() : new IllegalStateException("connect failed");
            onUpstreamFailure(state, key, future, cause, overallFuture);
            attemptConnect(index + 1, candidates, allowCoolingFallback, inbound, relayPromise, overallFuture, port, key.type(), cause);
            return;
        }

        if (isChannelStale(future, outboundIp)) {
            Throwable cause = new IllegalStateException("upstream bound to stale local address");
            onUpstreamFailure(state, key, future, cause, overallFuture);
            attemptConnect(index + 1, candidates, allowCoolingFallback, inbound, relayPromise, overallFuture, port, key.type(), cause);
            return;
        }

        Channel ch = future.channel();
        if (ready(ch)) {
            onUpstreamSuccess(state, overallFuture, relayPromise, ch);
            return;
        }

        SslHandler ssl = ch.pipeline().get(SslHandler.class);
        if (ssl != null) {
            Future<Channel> handshake = ssl.handshakeFuture();
            handshake.addListener(f -> {
                if (overallFuture.isDone()) {
                    return;
                }
                if (f.isSuccess() && ready(ch)) {
                    onUpstreamSuccess(state, overallFuture, relayPromise, ch);
                } else {
                    Throwable cause = f.cause() != null ? f.cause() : new IllegalStateException("TLS handshake not ready");
                    onUpstreamFailure(state, key, future, cause, overallFuture);
                    attemptConnect(index + 1, candidates, allowCoolingFallback, inbound, relayPromise, overallFuture, port, key.type(), cause);
                }
            });
            return;
        }

        Throwable cause = new IllegalStateException("Channel not ready");
        onUpstreamFailure(state, key, future, cause, overallFuture);
        attemptConnect(index + 1, candidates, allowCoolingFallback, inbound, relayPromise, overallFuture, port, key.type(), cause);
    }

    private void onUpstreamSuccess(UpstreamState state,
                                   ChainedChannelFuture overallFuture,
                                   Promise<Channel> relayPromise,
                                   Channel ch) {
        if (overallFuture.isDone() || relayPromise.isDone()) {
            return;
        }
        state.onSuccess();
        overallFuture.setChannel(ch);
        relayPromise.trySuccess(ch);
        overallFuture.trySuccess(null);
    }

    private void onUpstreamFailure(UpstreamState state,
                                   PoolKey key,
                                   ChannelFuture cf,
                                   Throwable cause,
                                   ChainedChannelFuture overallFuture) {
        long now = System.currentTimeMillis();
        state.onFailure(now);
        if (state.inCooldown(now)) {
            log.info("{}:{} enter cooldown until {}", key.host(), key.port(), state.coolDownUntilMillis());
        }
        log.warn("{} upstream {}:{} failed: {}", key.type(), key.host(), key.port(), cause.getMessage());
        if (cf.channel() != null) {
            overallFuture.setChannel(cf.channel());
        }
        POOL.remove(key, cf);
        Channel ch = cf.channel();
        if (ch != null && ch.isOpen()) {
            ch.close();
        }
    }

    private List<String> parseHosts(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        String[] parts = raw.split("[,\\s]+");
        List<String> hosts = new ArrayList<>(parts.length);
        for (String part : parts) {
            String host = part.trim();
            if (!host.isEmpty()) {
                hosts.add(host);
            }
        }
        return hosts;
    }

    private List<String> orderHosts(List<String> hosts) {
        if (hosts.size() <= 1) {
            return hosts;
        }
        int start = Math.floorMod(ROUND_ROBIN.getAndIncrement(), hosts.size());
        if (start == 0) {
            return hosts;
        }
        List<String> ordered = new ArrayList<>(hosts.size());
        ordered.addAll(hosts.subList(start, hosts.size()));
        ordered.addAll(hosts.subList(0, start));
        return ordered;
    }

    private List<String> pickAvailableHosts(List<String> orderedHosts,
                                            int port,
                                            ProtocolType type,
                                            long now) {
        List<String> available = new ArrayList<>(orderedHosts.size());
        for (String host : orderedHosts) {
            PoolKey key = new PoolKey(type, host, port);
            UpstreamState state = STATE.computeIfAbsent(key, k -> new UpstreamState());
            if (!state.inCooldown(now)) {
                available.add(host);
            }
        }
        return available;
    }

    private String probeOutboundIp(String host, int port) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(new InetSocketAddress(host, port));
            InetAddress local = socket.getLocalAddress();
            if (local != null && !local.isAnyLocalAddress()) {
                return local.getHostAddress();
            }
        } catch (Exception e) {
            log.debug("Failed to probe outbound IP for {}:{}: {}", host, port, e.getMessage());
        }
        return null;
    }

    private boolean isChannelStale(ChannelFuture cf, String expectedOutboundIp) {
        if (cf == null) {
            return true;
        }
        if (!cf.isDone()) {
            return false;
        }
        if (!cf.isSuccess()) {
            return true;
        }
        Channel ch = cf.channel();
        if (ch == null || !ch.isActive()) {
            return true;
        }
        SocketAddress local = ch.localAddress();
        if (!(local instanceof InetSocketAddress)) {
            return false;
        }
        InetAddress address = ((InetSocketAddress) local).getAddress();
        if (expectedOutboundIp != null
                && address != null
                && !address.isAnyLocalAddress()
                && !address.isLoopbackAddress()
                && !expectedOutboundIp.equals(address.getHostAddress())) {
            return true;
        }
        return !isAddressUsable(address);
    }

    private boolean isAddressUsable(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()) {
            return true;
        }
        try {
            NetworkInterface nif = NetworkInterface.getByInetAddress(address);
            return nif != null && nif.isUp();
        } catch (SocketException e) {
            log.warn("Failed to inspect local address {}: {}", address, e.getMessage());
            return true;
        }
    }

    private void closeChannelQuietly(Channel channel) {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
    }

    private static final class UpstreamState {
        private final AtomicInteger failures = new AtomicInteger();
        private volatile long coolDownUntilMillis;

        boolean inCooldown(long now) {
            return coolDownUntilMillis > now;
        }

        void onSuccess() {
            failures.set(0);
            coolDownUntilMillis = 0;
        }

        void onFailure(long now) {
            int f = failures.incrementAndGet();
            if (f >= FAILURE_THRESHOLD) {
                coolDownUntilMillis = now + COOLDOWN_MILLIS;
            }
        }

        long coolDownUntilMillis() {
            return coolDownUntilMillis;
        }
    }

    private static final class ChainedChannelFuture extends DefaultChannelPromise {
        private volatile Channel delegate;

        ChainedChannelFuture(Channel inbound) {
            super(inbound, inbound.eventLoop());
            this.delegate = inbound;
        }

        void setChannel(Channel channel) {
            this.delegate = channel;
        }

        @Override
        public Channel channel() {
            return delegate;
        }
    }
}
