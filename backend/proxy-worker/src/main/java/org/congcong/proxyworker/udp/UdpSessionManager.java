package org.congcong.proxyworker.udp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.concurrent.Future;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.config.*;
import org.congcong.proxyworker.dns.FakeIpRegistry;
import org.congcong.proxyworker.router.RouteSelector;
import org.congcong.proxyworker.server.AbstractProxyServer.RuntimeConfigSnapshot;
import org.congcong.proxyworker.server.tunnel.LogicalTargetSource;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class UdpSessionManager {
    private final EventLoop eventLoop;
    private final Supplier<RuntimeConfigSnapshot> config;
    private final UdpRelaySettings settings;
    private final UdpOutboundConnector connector;
    private final ReplySocketPool replySockets;
    private final UdpRelayCounters counters;
    private final LongSupplier clock;
    private final LinkedHashMap<UdpSession.Key, UdpSession> sessions = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Integer> clients = new HashMap<>();
    private boolean closed;

    public UdpSessionManager(EventLoop eventLoop, Supplier<RuntimeConfigSnapshot> config, UdpRelaySettings settings,
                             UdpOutboundConnector connector, ReplySocketPool replySockets, UdpRelayCounters counters) {
        this(eventLoop, config, settings, connector, replySockets, counters, System::nanoTime);
    }

    UdpSessionManager(EventLoop eventLoop, Supplier<RuntimeConfigSnapshot> config, UdpRelaySettings settings,
                      UdpOutboundConnector connector, ReplySocketPool replySockets, UdpRelayCounters counters,
                      LongSupplier clock) {
        this.eventLoop = eventLoop;
        this.config = config;
        this.settings = settings;
        this.connector = connector;
        this.replySockets = replySockets;
        this.counters = counters;
        this.clock = clock;
    }

    /** Takes ownership of data, even on rejection. */
    public void receive(InetSocketAddress client, InetSocketAddress target, ByteBuf data) {
        if (closed || !ipv4(client) || !ipv4(target)) {
            counters.drop("invalid");
            data.release();
            return;
        }
        UdpSession.Key key = new UdpSession.Key(client, target);
        UdpSession session = sessions.get(key);
        if (session != null && session.state == UdpSession.State.REJECTED && nanoTime() >= session.rejectedUntilNanos) {
            session.close("rejected_expired");
            session = null;
        }
        if (session == null) {
            String clientIp = client.getAddress().getHostAddress();
            if (clients.getOrDefault(clientIp, 0) >= settings.maxSessionsPerClient()) {
                counters.drop("per_client_limit");
                data.release();
                return;
            }
            String ip = target.getAddress().getHostAddress();
            boolean fake = FakeIpRegistry.isInPool(ip);
            String host = fake ? FakeIpRegistry.getInstance().reverseLookup(ip).orElse(null) : ip;
            String reject = null;
            ProxyTunnelRequest request = null;
            if (host == null) {
                reject = "fake_ip_miss";
            } else {
                RuntimeConfigSnapshot snapshot = config.get();
                UserConfig user = FindUser.find(clientIp, snapshot.inboundConfig());
                if (user == null) {
                    reject = "no_user";
                } else {
                    request = new ProxyTunnelRequest(ProtocolType.TP_PROXY,
                            fake ? FakeIpRegistry.normalizeDomain(host) : host, fake ? null : ip,
                            target.getPort(), user, snapshot.inboundConfig(), null);
                    request.setOriginalTargetIp(ip);
                    request.setLogicalTargetSource(fake ? LogicalTargetSource.FAKE_IP : LogicalTargetSource.ORIGINAL_IP);
                    request.setDnsInboundConfig(snapshot.dnsInboundConfig());
                    RouteConfig route = new RouteSelector().select(request, snapshot.inboundConfig(), user.getId()).route();
                    request.setRouteConfig(route);
                    if (!UdpRouteSupport.supports(route)) reject = "route_unsupported";
                }
            }
            // Before bind, DNS, connect, or any immediate future listener.
            session = insert(key, request, reject == null ? UdpSession.State.CONNECTING : UdpSession.State.REJECTED,
                    settings.rejectCacheSeconds());
            if (reject != null) {
                counters.drop(reject);
                data.release();
                return;
            }
            buffer(session, data);
            start(session);
            return;
        }
        touch(session);
        switch (session.state) {
            case REJECTED -> { counters.drop("rejected"); data.release(); }
            case CONNECTING -> buffer(session, data);
            case READY -> send(session, data);
        }
    }

    private UdpSession insert(UdpSession.Key key, ProxyTunnelRequest request, UdpSession.State state, int rejectSeconds) {
        makeRoom();
        UdpSession session = new UdpSession(this, key, request, state, rejectSeconds);
        sessions.put(key, session);
        clients.merge(key.client().getAddress().getHostAddress(), 1, Integer::sum);
        counters.increment("sessions_" + state.name().toLowerCase(Locale.ROOT));
        counters.increment("sessions_created");
        return session;
    }

    /** Setup failures leave a short rejection so client retransmits do not repeat bind, DNS and connect. */
    private void fail(UdpSession session, String reason) {
        if (session.closed) return;
        session.close(reason);
        if (!closed && !sessions.containsKey(session.key)) {
            insert(session.key, session.request, UdpSession.State.REJECTED, settings.failureCacheSeconds());
        }
    }

    private void start(UdpSession session) {
        try {
            session.reply = replySockets.acquire(session.key.target());
            session.reply.ready().addListener(done -> onLoop(() -> {
                if (session.closed) return;
                if (!done.isSuccess()) fail(session, "reply_bind_error");
                else ready(session);
            }));
            if (session.closed) return;
            Future<Channel> opening = connector.open(session, eventLoop);
            opening.addListener(done -> onLoop(() -> {
                if (!done.isSuccess()) {
                    fail(session, "outbound_error");
                    return;
                }
                Channel outbound = opening.getNow();
                if (session.closed) { outbound.close(); return; }
                session.outbound = outbound;
                outbound.closeFuture().addListener(ignored -> session.close("outbound_error"));
                ready(session);
            }));
        } catch (Exception failure) {
            fail(session, session.reply == null ? "reply_bind_error" : "outbound_error");
        }
    }

    private void ready(UdpSession session) {
        if (session.closed || session.state != UdpSession.State.CONNECTING || session.outbound == null
                || !session.outbound.isActive() || !session.reply.ready().isSuccess()) return;
        counters.add("sessions_connecting", -1);
        counters.increment("sessions_ready");
        session.state = UdpSession.State.READY;
        ByteBuf data;
        while ((data = session.pending.poll()) != null) {
            session.pendingBytes -= data.readableBytes();
            if (session.closed) data.release();
            else send(session, data);
        }
    }

    private void buffer(UdpSession session, ByteBuf data) {
        if (session.pending.size() >= settings.pendingMaxPackets()
                || data.readableBytes() > settings.pendingMaxBytes() - session.pendingBytes) {
            counters.drop("pending_full");
            data.release();
        } else {
            int size = data.readableBytes();
            if (data.capacity() > size) {
                // The listener reads into maxDatagramSize buffers; queue only the payload.
                ByteBuf compact = data.alloc().buffer(size, size).writeBytes(data);
                data.release();
                data = compact;
            }
            session.pending.add(data);
            session.pendingBytes += size;
        }
    }

    private void send(UdpSession session, ByteBuf data) {
        write(session, session.outbound, data, null, "uplink", "outbound_error");
    }

    /** Takes ownership of a payload received from the connected real destination. */
    public void reply(UdpSession session, ByteBuf data) {
        if (session.closed || session.state != UdpSession.State.READY) {
            counters.drop("not_ready");
            data.release();
            return;
        }
        touch(session); // Downlink-only sessions also refresh the LRU and idle clock.
        write(session, session.reply.channel(), data, session.key.client(), "downlink", "reply_write_error");
    }

    private void write(UdpSession session, Channel channel, ByteBuf data, InetSocketAddress recipient,
                       String direction, String failureReason) {
        if (!channel.isActive() || !channel.isWritable()) {
            counters.drop("unwritable");
            data.release();
            return;
        }
        int size = data.readableBytes();
        Object message = recipient == null ? data : new DatagramPacket(data, recipient);
        channel.writeAndFlush(message).addListener(done -> {
            if (done.isSuccess()) {
                counters.increment(direction + "_packets");
                counters.add(direction + "_bytes", size);
            } else session.close(failureReason);
        });
    }

    private void makeRoom() {
        if (sessions.size() < settings.maxSessions()) return;
        UdpSession victim = sessions.values().stream().filter(s -> s.state == UdpSession.State.REJECTED)
                .findFirst().orElse(sessions.values().iterator().next());
        victim.close("evicted");
    }

    void touch(UdpSession session) {
        if (session.closed) return;
        sessions.get(session.key);
        session.lastActivityNanos = nanoTime();
    }

    /** Also bounds connecting sessions while DNS is pending and expires cold rejection entries. */
    public void sweep() {
        long now = nanoTime();
        for (UdpSession session : List.copyOf(sessions.values())) {
            if (session.state == UdpSession.State.REJECTED) {
                if (now >= session.rejectedUntilNanos) session.close("rejected_expired");
            } else if (session.state == UdpSession.State.CONNECTING) {
                // Measured from creation: client retransmits must not keep a stuck setup alive.
                if (now - session.createdNanos >= TimeUnit.SECONDS.toNanos(settings.connectTimeoutSeconds())) {
                    fail(session, "connect_timeout");
                }
            } else if (now - session.lastActivityNanos >= TimeUnit.SECONDS.toNanos(settings.idleTimeoutSeconds())) {
                session.close("idle");
            }
        }
    }

    void remove(UdpSession session) {
        if (sessions.remove(session.key, session)) {
            String client = session.key.client().getAddress().getHostAddress();
            clients.computeIfPresent(client, (ignored, count) -> count == 1 ? null : count - 1);
            counters.add("sessions_" + session.state.name().toLowerCase(Locale.ROOT), -1);
        }
    }

    public void close() {
        closed = true;
        for (UdpSession session : List.copyOf(sessions.values())) session.close("server_close");
    }

    private boolean ipv4(InetSocketAddress address) {
        return address != null && address.getAddress() instanceof Inet4Address && address.getPort() > 0;
    }

    private void onLoop(Runnable action) {
        if (eventLoop.inEventLoop()) action.run();
        else eventLoop.execute(action);
    }

    long nanoTime() { return clock.getAsLong(); }
    public int size() { return sessions.size(); }
    public UdpRelaySettings settings() { return settings; }
    public ReplySocketPool replySockets() { return replySockets; }
    public UdpRelayCounters counters() { return counters; }
}
