package org.congcong.proxyworker.dns;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.net.InetAddresses;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponseCode;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.proxyworker.config.RouteConfig;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Shared policy and answer path for DNS clients and worker-originated address lookups. */
public final class DnsQueryService {
    private static final DnsQueryService INSTANCE = new DnsQueryService();
    private final DnsUpstreamPool upstream = new DnsUpstreamPool();
    private final Cache<Key, Cached> answers = CacheBuilder.newBuilder()
            .maximumSize(10_000).expireAfterWrite(300, TimeUnit.SECONDS).build();
    private final ConcurrentMap<Key, Flight> inFlight = new ConcurrentHashMap<>();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder upstreamQueries = new LongAdder();
    private final LongAdder upstreamFailures = new LongAdder();
    private final ConcurrentMap<Long, LongAdder> queriesByRoute = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, LongAdder> cacheHitsByRoute = new ConcurrentHashMap<>();

    private DnsQueryService() {}

    public static DnsQueryService getInstance() { return INSTANCE; }

    public CompletableFuture<DnsResult> query(String domain, DnsRecordType type, QueryMode mode,
                                              DnsQueryContext context, EventLoop loop) {
        if (context == null || context.dnsInbound() == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("DNS 入站配置不可用"));
        }
        if (type != DnsRecordType.A && type != DnsRecordType.AAAA) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("unsupported address question: " + type));
        }
        String normalized = DnsNames.normalize(domain);
        DnsRouteSelector.Selection selection = DnsRouteSelector.select(context.dnsInbound(), context.clientIp(), normalized);
        RouteConfig route = selection.route();
        Long routeId = route.getId();
        if (route.getPolicy() == RoutePolicy.BLOCK) {
            return CompletableFuture.completedFuture(DnsResult.blocked(routeId));
        }
        if (route.getPolicy() == RoutePolicy.DNS_REWRITING) {
            return CompletableFuture.completedFuture(rewrite(route, type));
        }
        if (!isDnsForward(route)) {
            return CompletableFuture.failedFuture(new IllegalStateException("选中的 DNS 路由不是有效的转发路由"));
        }
        if (mode == QueryMode.CLIENT_FAKE && Boolean.TRUE.equals(context.dnsInbound().getFakeIpEnabled())
                && DnsNames.isHostname(normalized)) {
            if (type == DnsRecordType.AAAA) {
                return CompletableFuture.completedFuture(new DnsResult(DnsResponseCode.NOERROR, List.of(),
                        0, 0, true, routeId));
            }
            String address = FakeIpRegistry.getInstance().getOrAllocate(normalized);
            return CompletableFuture.completedFuture(new DnsResult(DnsResponseCode.NOERROR, List.of(address),
                    1, 0, true, routeId));
        }

        Key key = new Key(normalized, type, routeId, selection.user().getId(), context.configVersion(),
                route.getOutboundProxyType(), route.getOutboundProxyHost(), route.getOutboundProxyPort());
        Cached cached = answers.getIfPresent(key);
        if (cached != null && cached.deadlineNanos() > System.nanoTime()) {
            cacheHits.increment();
            increment(cacheHitsByRoute, routeId);
            return CompletableFuture.completedFuture(cached.remaining());
        }
        Flight created = new Flight(key);
        for (;;) {
            Flight existing = inFlight.putIfAbsent(key, created);
            if (existing == null) break;
            CompletableFuture<DnsResult> attached = existing.subscribe();
            if (attached != null) return attached;
            inFlight.remove(key, existing);
        }
        CompletableFuture<DnsResult> subscriber = created.subscribe();
        CompletableFuture<DnsResult> upstreamQuery = queryUpstream(route, normalized, type, loop, 0,
                Boolean.TRUE.equals(context.dnsInbound().getFakeIpEnabled()));
        created.upstream = upstreamQuery;
        if (created.result.isCancelled()) upstreamQuery.cancel(false);
        upstreamQuery.whenComplete((result, failure) -> {
            try {
                if (failure != null) {
                    upstreamFailures.increment();
                    created.result.completeExceptionally(failure);
                } else {
                    if (!created.result.isDone()) {
                        int lifetime = cacheLifetime(result);
                        if (lifetime > 0) answers.put(key, new Cached(result,
                                System.nanoTime() + TimeUnit.SECONDS.toNanos(lifetime)));
                        created.result.complete(result);
                    }
                }
            } finally {
                inFlight.remove(key, created);
            }
        });
        return subscriber;
    }

    public long cacheHits() { return cacheHits.sum(); }
    public long upstreamQueries() { return upstreamQueries.sum(); }
    public long upstreamFailures() { return upstreamFailures.sum(); }
    public Map<Long, Long> queriesByRoute() { return snapshot(queriesByRoute); }
    public Map<Long, Long> cacheHitsByRoute() { return snapshot(cacheHitsByRoute); }
    public Map<Long, Long> timeoutsByRoute() { return upstream.timeoutsByRoute(); }
    public Map<Long, Long> truncationsByRoute() { return upstream.truncationsByRoute(); }
    CompletableFuture<Integer> pendingCount(EventLoop loop) { return upstream.pendingCount(loop); }
    int inFlightCount() { return inFlight.size(); }

    private static void increment(ConcurrentMap<Long, LongAdder> counters, Long routeId) {
        counters.computeIfAbsent(routeId == null ? -1L : routeId, ignored -> new LongAdder()).increment();
    }

    static Map<Long, Long> snapshot(ConcurrentMap<Long, LongAdder> counters) {
        return counters.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> entry.getValue().sum()));
    }

    private CompletableFuture<DnsResult> queryUpstream(RouteConfig route, String domain, DnsRecordType type,
                                                       EventLoop loop, int index, boolean literalOnly) {
        String raw = route.getOutboundProxyHost();
        Integer port = route.getOutboundProxyPort();
        if (raw == null || raw.isBlank() || port == null || port < 1 || port > 65535) {
            return CompletableFuture.failedFuture(new IllegalStateException("DNS 上游端点无效"));
        }
        String[] hosts = Arrays.stream(raw.trim().split("[,\\s]+"))
                .filter(host -> !host.isBlank()).toArray(String[]::new);
        if (index >= hosts.length) {
            return CompletableFuture.failedFuture(new IllegalStateException("所有 DNS 上游均失败"));
        }
        upstreamQueries.increment();
        increment(queriesByRoute, route.getId());
        CompletableFuture<DnsResult> result = new CompletableFuture<>();
        CompletableFuture<DnsResult> first = literalOnly && !InetAddresses.isInetAddress(hosts[index])
                ? CompletableFuture.failedFuture(new IllegalArgumentException("Fake IP DNS upstream must be an IP literal"))
                : upstream.query(loop, route.getOutboundProxyType(), hosts[index], port, domain, type, route.getId());
        AtomicReference<CompletableFuture<DnsResult>> active = new AtomicReference<>(first);
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) active.get().cancel(false);
        });
        first.whenComplete((answer, error) -> {
            if (result.isCancelled()) return;
            if (error == null && answer.code() != DnsResponseCode.SERVFAIL) {
                result.complete(answer);
                return;
            }
            CompletableFuture<DnsResult> next = queryUpstream(route, domain, type, loop, index + 1, literalOnly);
            active.set(next);
            if (result.isCancelled()) next.cancel(false);
            else next.whenComplete((retried, retryFailure) -> {
                if (retryFailure == null) result.complete(retried);
                else result.completeExceptionally(retryFailure);
            });
        });
        return result;
    }

    private int cacheLifetime(DnsResult result) {
        if (result.code() == DnsResponseCode.NOERROR && !result.addresses().isEmpty()) {
            return Math.max(0, Math.min(300, result.ttlSeconds()));
        }
        if (result.code() == DnsResponseCode.NXDOMAIN
                || (result.code() == DnsResponseCode.NOERROR && result.addresses().isEmpty())) {
            return Math.max(0, Math.min(30, result.negativeTtlSeconds()));
        }
        return 0;
    }

    private DnsResult rewrite(RouteConfig route, DnsRecordType type) {
        String address = route.getOutboundProxyHost();
        if (address == null || !InetAddresses.isInetAddress(address)) {
            return new DnsResult(DnsResponseCode.SERVFAIL, List.of(), 0, 0, false, route.getId());
        }
        InetAddress parsed = InetAddresses.forString(address);
        boolean compatible = parsed.getAddress().length == (type == DnsRecordType.A ? 4 : 16);
        return new DnsResult(DnsResponseCode.NOERROR, compatible ? List.of(address) : List.of(),
                60, 0, false, route.getId());
    }

    private boolean isDnsForward(RouteConfig route) {
        return route.getPolicy() == RoutePolicy.OUTBOUND_PROXY
                && (route.getOutboundProxyType() == ProtocolType.DNS_SERVER
                || route.getOutboundProxyType() == ProtocolType.DOT);
    }

    private record Key(String domain, DnsRecordType type, Long routeId, Long userId, String version,
                       ProtocolType upstreamType, String upstreamHosts, Integer upstreamPort) {}

    private record Cached(DnsResult result, long deadlineNanos) {
        private DnsResult remaining() {
            int seconds = (int) Math.max(0, TimeUnit.NANOSECONDS.toSeconds(deadlineNanos - System.nanoTime()));
            return new DnsResult(result.code(), result.addresses(), seconds, seconds, result.fake(), result.routeId());
        }
    }

    private final class Flight {
        private final Key key;
        private final CompletableFuture<DnsResult> result = new CompletableFuture<>();
        private final AtomicInteger subscribers = new AtomicInteger();
        private volatile CompletableFuture<DnsResult> upstream;

        private Flight(Key key) { this.key = key; }

        private synchronized CompletableFuture<DnsResult> subscribe() {
            if (result.isCancelled()) return null;
            subscribers.incrementAndGet();
            CompletableFuture<DnsResult> subscriber = new CompletableFuture<>();
            result.whenComplete((answer, failure) -> {
                if (failure == null) subscriber.complete(answer);
                else subscriber.completeExceptionally(failure);
            });
            subscriber.whenComplete((ignored, failure) -> {
                synchronized (this) {
                    if (subscribers.decrementAndGet() == 0 && !result.isDone()) {
                        inFlight.remove(key, this);
                        result.cancel(false);
                        CompletableFuture<DnsResult> pending = upstream;
                        if (pending != null) pending.cancel(false);
                    }
                }
            });
            return subscriber;
        }
    }
}
