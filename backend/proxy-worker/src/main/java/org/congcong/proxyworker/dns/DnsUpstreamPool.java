package org.congcong.proxyworker.dns;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.dns.*;
import io.netty.handler.ssl.SslHandler;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.server.netty.tls.TlsClientContextManager;
import org.congcong.proxyworker.security.ProxySafetyCounters;

import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/** Nonblocking, pooled UDP DNS and DoT queries. A pending ID belongs to one upstream channel. */
@Slf4j
final class DnsUpstreamPool {
    private static final long QUERY_TIMEOUT_SECONDS = 5;
    private static final int MAX_CONSECUTIVE_TIMEOUTS = 2;
    private final Map<Key, Transport> transports = new ConcurrentHashMap<>();
    private final LongAdder truncations = new LongAdder();
    private final ConcurrentMap<Long, LongAdder> timeoutsByRoute = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, LongAdder> truncationsByRoute = new ConcurrentHashMap<>();

    CompletableFuture<DnsResult> query(EventLoop loop, ProtocolType type, String host, int port,
                                       String domain, DnsRecordType qType, Long routeId) {
        CompletableFuture<DnsResult> first = querySingle(loop, type, false, host, port, domain, qType, routeId);
        if (type != ProtocolType.DNS_SERVER) return first;
        CompletableFuture<DnsResult> result = new CompletableFuture<>();
        AtomicReference<CompletableFuture<DnsResult>> active = new AtomicReference<>(first);
        result.whenComplete((ignored, error) -> {
            if (result.isCancelled()) active.get().cancel(false);
        });
        first.whenComplete((answer, error) -> {
            if (result.isCancelled()) return;
            if (error instanceof TruncatedResponseException
                    || (error != null && error.getCause() instanceof TruncatedResponseException)) {
                truncations.increment();
                truncationsByRoute.computeIfAbsent(routeId == null ? -1L : routeId,
                        ignored -> new LongAdder()).increment();
                CompletableFuture<DnsResult> retry = querySingle(loop, type, true, host, port, domain, qType, routeId);
                active.set(retry);
                if (result.isCancelled()) retry.cancel(false);
                else retry.whenComplete((retried, retryError) -> {
                    if (retryError == null) result.complete(retried);
                    else result.completeExceptionally(retryError);
                });
                return;
            }
            if (error == null) result.complete(answer);
            else result.completeExceptionally(error);
        });
        return result;
    }

    long truncations() { return truncations.sum(); }
    Map<Long, Long> timeoutsByRoute() { return DnsQueryService.snapshot(timeoutsByRoute); }
    Map<Long, Long> truncationsByRoute() { return DnsQueryService.snapshot(truncationsByRoute); }

    CompletableFuture<Integer> pendingCount(EventLoop loop) {
        CompletableFuture<Integer> result = new CompletableFuture<>();
        loop.execute(() -> result.complete(transports.entrySet().stream()
                .filter(entry -> entry.getKey().loop() == loop)
                .mapToInt(entry -> entry.getValue().pending.size()).sum()));
        return result;
    }

    private CompletableFuture<DnsResult> querySingle(EventLoop loop, ProtocolType type, boolean tcp,
                                                     String host, int port, String domain,
                                                     DnsRecordType qType, Long routeId) {
        if (host == null || host.isBlank() || FakeIpRegistry.isInPool(host)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid DNS upstream host"));
        }
        Key key = new Key(loop, type, tcp, host, port);
        CompletableFuture<DnsResult> answer = new CompletableFuture<>();
        try {
            loop.execute(() -> {
                try {
                    if (answer.isCancelled()) return;
                    // Select the transport on its owning event loop. A queued
                    // request must not capture a transport retired by a prior write.
                    Transport transport = transports.computeIfAbsent(key, Transport::new);
                    if (transport.isStale()) {
                        transport.retire(new IllegalStateException("DNS upstream bound to stale local address or channel"));
                        transport = transports.computeIfAbsent(key, Transport::new);
                    }
                    transport.send(domain, qType, routeId, answer);
                } catch (RuntimeException failure) {
                    answer.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            answer.completeExceptionally(failure);
        }
        return answer;
    }

    private record Key(EventLoop loop, ProtocolType type, boolean tcp, String host, int port) {}

    private static final class TruncatedResponseException extends Exception {
        private TruncatedResponseException() { super("DNS response truncated"); }
    }

    private final class Transport {
        private final Key key;
        private final Map<Integer, Pending> pending = new HashMap<>();
        private final AtomicInteger nextId = new AtomicInteger();
        private ChannelFuture connection;
        private int consecutiveTimeouts;
        private boolean retired;

        private Transport(Key key) { this.key = key; }

        private void send(String domain, DnsRecordType type, Long routeId, CompletableFuture<DnsResult> answer) {
            if (answer.isCancelled()) return;
            if (connection == null) {
                connection = connect();
            }
            ChannelFuture current = connection;
            current.addListener((ChannelFutureListener) connected -> {
                if (!connected.isSuccess()) {
                    retire(connected.cause());
                    answer.completeExceptionally(connected.cause());
                    return;
                }
                Channel channel = connected.channel();
                SslHandler ssl = channel.pipeline().get(SslHandler.class);
                if (ssl != null && !ssl.handshakeFuture().isDone()) {
                    ssl.handshakeFuture().addListener(done -> {
                        if (done.isSuccess()) write(channel, domain, type, routeId, answer);
                        else answer.completeExceptionally(done.cause());
                    });
                } else if (ssl != null && !ssl.handshakeFuture().isSuccess()) {
                    answer.completeExceptionally(ssl.handshakeFuture().cause());
                } else {
                    write(channel, domain, type, routeId, answer);
                }
            });
        }

        private boolean isStale() {
            if (connection == null || !connection.isDone()) return false;
            Channel channel = connection.channel();
            if (!connection.isSuccess() || !channel.isActive()) return true;
            if (!(channel.localAddress() instanceof InetSocketAddress local) || local.getAddress() == null
                    || local.getAddress().isAnyLocalAddress()) return false;
            try {
                // A connected UDP socket retains its source IP after PPPoE/WAN
                // address changes, even while Netty still reports it as active.
                NetworkInterface network = NetworkInterface.getByInetAddress(local.getAddress());
                return network == null || !network.isUp();
            } catch (SocketException failure) {
                log.debug("Cannot inspect DNS upstream local address {}: {}", local, failure.toString());
                return false;
            }
        }

        private void retire(Throwable cause) {
            if (retired) return;
            retired = true;
            transports.remove(key, this);
            log.warn("重建失效 DNS 上游连接: host={}, port={}, reason={}", key.host(), key.port(), cause.toString());
            failAll(cause);
            if (connection != null) connection.channel().close();
        }

        private ChannelFuture connect() {
            boolean epoll = Epoll.isAvailable() && key.loop().parent() instanceof EpollEventLoopGroup;
            Bootstrap bootstrap = new Bootstrap().group(key.loop())
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000);
            if (key.type() == ProtocolType.DOT || key.tcp()) {
                bootstrap.channel(epoll ? EpollSocketChannel.class : NioSocketChannel.class)
                        .handler(new ChannelInitializer<Channel>() {
                            @Override protected void initChannel(Channel channel) {
                                if (key.type() == ProtocolType.DOT) {
                                    channel.pipeline().addLast(TlsClientContextManager.getInstance()
                                            .getClientContext().newHandler(channel.alloc(), key.host(), key.port()));
                                }
                                channel.pipeline().addLast(new TcpDnsQueryEncoder(), new TcpDnsResponseDecoder());
                                channel.pipeline().addLast(new ResponseHandler());
                            }
                        });
            } else {
                bootstrap.channel(epoll ? EpollDatagramChannel.class : NioDatagramChannel.class)
                        .handler(new ChannelInitializer<Channel>() {
                            @Override protected void initChannel(Channel channel) {
                                channel.pipeline().addLast(new DatagramDnsQueryEncoder(), new DatagramDnsResponseDecoder());
                                channel.pipeline().addLast(new ResponseHandler());
                            }
                        });
            }
            // Bootstrap resolves configured hostnames with the system resolver,
            // preserving the legacy DNS/DoT behavior when Fake IP is disabled.
            ChannelFuture future = bootstrap.connect(key.host(), key.port());
            future.channel().closeFuture().addListener(ignored -> {
                retire(new IllegalStateException("DNS upstream channel closed"));
            });
            return future;
        }

        private void write(Channel channel, String domain, DnsRecordType type, Long routeId,
                           CompletableFuture<DnsResult> answer) {
            if (answer.isCancelled()) return;
            if (retired || !channel.isActive()) {
                answer.completeExceptionally(new IllegalStateException("DNS upstream channel unavailable"));
                return;
            }
            String normalized = DnsNames.normalize(domain);
            int id = allocateId();
            ScheduledFuture<?> timeout = key.loop().schedule(() -> {
                Pending removed = pending.remove(id);
                if (removed != null) {
                    timeoutsByRoute.computeIfAbsent(routeId == null ? -1L : routeId,
                            ignored -> new LongAdder()).increment();
                    TimeoutException failure = new TimeoutException("DNS upstream timeout");
                    if (++consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) retire(failure);
                    removed.answer().completeExceptionally(failure);
                }
            }, QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            pending.put(id, new Pending(normalized, type, routeId, answer, timeout));
            answer.whenComplete((ignored, cause) -> {
                if (answer.isCancelled()) key.loop().execute(() -> removePending(id));
            });

            DnsQuestion question = new DefaultDnsQuestion(normalized.equals(".") ? "." : normalized + ".", type);
            DnsQuery query;
            if (key.type() == ProtocolType.DOT || key.tcp()) {
                query = new DefaultDnsQuery(id);
            } else {
                query = new DatagramDnsQuery(null, (InetSocketAddress) channel.remoteAddress(), id);
            }
            query.setRecursionDesired(true);
            query.addRecord(DnsSection.QUESTION, question);
            channel.writeAndFlush(query).addListener((ChannelFutureListener) sent -> {
                if (!sent.isSuccess()) {
                    retire(sent.cause());
                    answer.completeExceptionally(sent.cause());
                }
            });
        }

        private int allocateId() {
            for (int i = 0; i < 65_536; i++) {
                int id = nextId.getAndIncrement() & 0xffff;
                if (!pending.containsKey(id)) return id;
            }
            throw new IllegalStateException("DNS upstream pending ID space exhausted");
        }

        private void removePending(int id) {
            Pending entry = pending.remove(id);
            if (entry != null) entry.timeout().cancel(false);
        }

        private void failAll(Throwable cause) {
            List<Pending> abandoned = new ArrayList<>(pending.values());
            pending.clear();
            for (Pending entry : abandoned) {
                entry.timeout().cancel(false);
                entry.answer().completeExceptionally(cause);
            }
        }

        private final class ResponseHandler extends SimpleChannelInboundHandler<DnsResponse> {
            @Override protected void channelRead0(ChannelHandlerContext ctx, DnsResponse response) {
                Pending entry = pending.get(response.id());
                if (entry == null) return;
                try {
                    completeResponse(response, entry);
                } catch (RuntimeException failure) {
                    // Parsing can fail after correlation and timeout removal. The
                    // channel-close handler can no longer find this query then.
                    removePending(response.id());
                    entry.answer().completeExceptionally(failure);
                }
            }

            private void completeResponse(DnsResponse response, Pending entry) {
                DnsQuestion question = response.recordAt(DnsSection.QUESTION);
                if (question == null || question.type() != entry.type()
                        || !entry.domain().equals(DnsNames.normalize(question.name()))) return;
                consecutiveTimeouts = 0;
                removePending(response.id());
                if (response.isTruncated()) {
                    entry.answer().completeExceptionally(new TruncatedResponseException());
                    return;
                }
                List<String> addresses = new ArrayList<>();
                Map<String, DnsRecord> aliases = new HashMap<>();
                for (int i = 0; i < response.count(DnsSection.ANSWER); i++) {
                    DnsRecord record = response.recordAt(DnsSection.ANSWER, i);
                    if (record.type() == DnsRecordType.CNAME) {
                        aliases.put(DnsNames.normalize(record.name()), record);
                    }
                }
                String answerName = entry.domain();
                long minTtl = Long.MAX_VALUE;
                Set<String> visited = new HashSet<>();
                for (int depth = 0; aliases.containsKey(answerName); depth++) {
                    if (depth >= 16 || !visited.add(answerName)) {
                        entry.answer().completeExceptionally(new IllegalStateException("DNS CNAME chain is cyclic or too long"));
                        return;
                    }
                    DnsRecord cname = aliases.get(answerName);
                    if (!(cname instanceof DnsRawRecord raw)) {
                        entry.answer().completeExceptionally(new IllegalStateException("DNS CNAME record is invalid"));
                        return;
                    }
                    minTtl = Math.min(minTtl, cname.timeToLive());
                    answerName = DnsNames.normalize(
                            DefaultDnsRecordDecoder.decodeName(raw.content().duplicate()));
                }
                for (int i = 0; i < response.count(DnsSection.ANSWER); i++) {
                    DnsRecord record = response.recordAt(DnsSection.ANSWER, i);
                    if (record.type() == entry.type()
                            && answerName.equals(DnsNames.normalize(record.name()))
                            && record instanceof DnsRawRecord raw) {
                        int length = entry.type() == DnsRecordType.A ? 4 : 16;
                        if (raw.content().readableBytes() != length) continue;
                        minTtl = Math.min(minTtl, record.timeToLive());
                        byte[] bytes = new byte[length];
                        raw.content().getBytes(raw.content().readerIndex(), bytes);
                        String address = io.netty.util.NetUtil.bytesToIpAddress(bytes);
                        if (FakeIpRegistry.isInPool(address)) {
                            ProxySafetyCounters.rejectedPoolAddress();
                            entry.answer().completeExceptionally(new IllegalStateException("DNS upstream returned Fake IP pool address"));
                            return;
                        }
                        addresses.add(address);
                    }
                }
                int negativeTtl = negativeTtl(response);
                int ttl = minTtl == Long.MAX_VALUE ? 0 : (int) Math.min(minTtl, Integer.MAX_VALUE);
                entry.answer().complete(new DnsResult(response.code(), addresses, ttl, negativeTtl,
                        false, entry.routeId()));
            }

            @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                ctx.close();
            }
        }

        private int negativeTtl(DnsResponse response) {
            for (int i = 0; i < response.count(DnsSection.AUTHORITY); i++) {
                DnsRecord record = response.recordAt(DnsSection.AUTHORITY, i);
                if (record.type() != DnsRecordType.SOA || !(record instanceof DnsRawRecord raw)
                        || raw.content().readableBytes() < 4) continue;
                int position = raw.content().writerIndex() - 4;
                long minimum = raw.content().getUnsignedInt(position);
                return (int) Math.min(30, Math.min(record.timeToLive(), minimum));
            }
            return 0;
        }
    }

    private record Pending(String domain, DnsRecordType type, Long routeId,
                           CompletableFuture<DnsResult> answer, ScheduledFuture<?> timeout) {}
}
