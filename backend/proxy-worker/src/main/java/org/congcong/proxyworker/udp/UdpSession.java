package org.congcong.proxyworker.udp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** All state, including async completion handling, belongs to the listener event loop. */
public final class UdpSession {
    public enum State { CONNECTING, READY, REJECTED }
    public record Key(InetSocketAddress client, InetSocketAddress target) {}

    final UdpSessionManager manager;
    final Key key;
    final ProxyTunnelRequest request;
    final ArrayDeque<ByteBuf> pending = new ArrayDeque<>();
    State state;
    boolean closed;
    int pendingBytes;
    long lastActivityNanos;
    final long createdNanos;
    final long rejectedUntilNanos;
    Channel outbound;
    Channel connectingChannel;
    ReplySocketPool.ReplySocket reply;
    CompletableFuture<?> dnsLookup;

    UdpSession(UdpSessionManager manager, Key key, ProxyTunnelRequest request, State state, int rejectSeconds) {
        this.manager = manager;
        this.key = key;
        this.request = request;
        this.state = state;
        createdNanos = manager.nanoTime();
        lastActivityNanos = createdNanos;
        rejectedUntilNanos = createdNanos + TimeUnit.SECONDS.toNanos(rejectSeconds);
    }

    public Key key() { return key; }
    public ProxyTunnelRequest request() { return request; }
    public State state() { return state; }
    public boolean isClosed() { return closed; }

    public void close(String reason) {
        if (closed) return;
        closed = true;
        manager.remove(this);
        if (dnsLookup != null) dnsLookup.cancel(false);
        ByteBuf data;
        while ((data = pending.poll()) != null) data.release();
        pendingBytes = 0;
        if (outbound != null) outbound.close();
        if (connectingChannel != null) connectingChannel.close();
        if (reply != null) manager.replySockets().release(key.target(), reply);
        manager.counters().increment("session_closed_" + reason);
    }
}
