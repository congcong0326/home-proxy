package org.congcong.proxyworker.udp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.concurrent.Promise;
import org.congcong.common.enums.*;
import org.congcong.proxyworker.ProxyWorkerTestFixtures;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.dns.FakeIpRegistry;
import org.congcong.proxyworker.server.AbstractProxyServer.RuntimeConfigSnapshot;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class UdpSessionManagerTest {
    private static final InetSocketAddress TARGET = new InetSocketAddress("203.0.113.1", 443);
    private static InetSocketAddress client(int port) { return new InetSocketAddress("192.0.2.2", port); }
    private static ByteBuf bytes(int value) { return Unpooled.buffer(1).writeByte(value); }

    private static final class Harness implements AutoCloseable {
        final EmbeddedChannel listener = new EmbeddedChannel();
        final UdpRelayCounters counters = new UdpRelayCounters();
        final AtomicLong now = new AtomicLong();
        final AtomicReference<RuntimeConfigSnapshot> config = new AtomicReference<>(
                new RuntimeConfigSnapshot(ProxyWorkerTestFixtures.inbound(ProtocolType.TP_PROXY), null));
        final List<EmbeddedChannel> replies = new ArrayList<>();
        final List<ChannelPromise> bindings = new ArrayList<>();
        final List<UdpSession> opened = new ArrayList<>();
        final List<Promise<Channel>> connections = new ArrayList<>();
        final List<EmbeddedChannel> outbounds = new ArrayList<>();
        final ReplySocketPool pool = new ReplySocketPool(listener.eventLoop(), counters) {
            @Override protected ChannelFuture bind(InetSocketAddress target) {
                EmbeddedChannel channel = new EmbeddedChannel();
                replies.add(channel);
                ChannelPromise promise = channel.newPromise();
                bindings.add(promise);
                return promise;
            }
        };
        final UdpSessionManager manager;
        Harness() { this(new UdpRelaySettings(2, 3, 3, 2, 3, 1, 65535, 2, 1)); }
        Harness(UdpRelaySettings settings) {
            manager = new UdpSessionManager(listener.eventLoop(), config::get, settings, (session, loop) -> {
                opened.add(session);
                Promise<Channel> promise = loop.newPromise();
                connections.add(promise);
                return promise;
            }, pool, counters, now::get);
        }
        EmbeddedChannel connect(int index) {
            EmbeddedChannel channel = new EmbeddedChannel();
            outbounds.add(channel);
            connections.get(index).setSuccess(channel);
            return channel;
        }
        void advance(int seconds) { now.addAndGet(TimeUnit.SECONDS.toNanos(seconds)); }
        @Override public void close() {
            manager.close();
            outbounds.forEach(EmbeddedChannel::finishAndReleaseAll);
            replies.forEach(EmbeddedChannel::finishAndReleaseAll);
            listener.finishAndReleaseAll();
        }
    }

    @Test void buffersOneFlowInOrderUntilBothSocketsAreReady() {
        try (Harness h = new Harness()) {
            h.manager.receive(client(1), TARGET, bytes(10));
            h.manager.receive(client(1), TARGET, bytes(11));
            assertEquals(1, h.opened.size());
            EmbeddedChannel outbound = h.connect(0);
            assertNull(outbound.readOutbound());
            h.bindings.get(0).setSuccess();
            assertEquals(UdpSession.State.READY, h.opened.get(0).state());
            for (int value : new int[]{10, 11}) {
                ByteBuf data = outbound.readOutbound();
                assertEquals(value, data.readByte());
                data.release();
            }
        }
    }

    @Test void capsPacketsAndBytesAndReleasesDroppedAndClosedBuffers() {
        try (Harness h = new Harness()) {
            ByteBuf first = bytes(1), second = bytes(2), packetsFull = bytes(3);
            h.manager.receive(client(1), TARGET, first);
            h.manager.receive(client(1), TARGET, second);
            h.manager.receive(client(1), TARGET, packetsFull);
            ByteBuf tooLarge = Unpooled.buffer(4).writeInt(1);
            h.manager.receive(client(2), TARGET, tooLarge);
            assertEquals(0, packetsFull.refCnt());
            assertEquals(0, tooLarge.refCnt());
            h.manager.close();
            assertEquals(0, first.refCnt());
            assertEquals(0, second.refCnt());
            assertEquals(2, h.counters.get("drop_pending_full"));
            assertEquals(0, h.pool.size());
        }
    }

    @Test void rejectsThenReevaluatesAfterExpirationAndUsesNewRuntimeSnapshot() {
        try (Harness h = new Harness()) {
            h.config.get().inboundConfig().getDefaultRouteConfig().setPolicy(RoutePolicy.OUTBOUND_PROXY);
            ByteBuf data = bytes(1);
            h.manager.receive(client(1), TARGET, data);
            h.manager.receive(client(1), TARGET, bytes(2));
            assertEquals(0, h.opened.size());
            assertEquals(1, h.counters.get("sessions_rejected"));
            assertEquals(1, h.counters.get("drop_route_unsupported"));
            assertEquals(1, h.counters.get("drop_rejected"));
            InboundConfig refreshed = ProxyWorkerTestFixtures.inbound(ProtocolType.TP_PROXY);
            h.config.set(new RuntimeConfigSnapshot(refreshed, refreshed));
            h.advance(2);
            h.manager.receive(client(1), TARGET, bytes(3));
            assertEquals(1, h.opened.size());
            assertSame(refreshed, h.opened.get(0).request().getInboundConfig());
            assertSame(refreshed, h.opened.get(0).request().getDnsInboundConfig());
            assertEquals(0, data.refCnt());
        }
    }

    @Test void sharesReplySocketAndCloseIsIdempotentAndLateCompletionClosesChannel() {
        try (Harness h = new Harness()) {
            h.manager.receive(client(1), TARGET, bytes(1));
            h.manager.receive(client(2), TARGET, bytes(2));
            assertEquals(1, h.pool.size());
            assertEquals(2, h.opened.get(0).reply.references());
            UdpSession first = h.opened.get(0);
            first.close("evicted");
            first.close("evicted");
            assertEquals(1, h.opened.get(1).reply.references());
            assertFalse(h.connect(0).isOpen());
            h.bindings.get(0).setFailure(new IllegalStateException("bind failed"));
            assertTrue(h.opened.get(1).isClosed());
            assertEquals(0, h.pool.size());
            assertEquals(1, h.counters.get("session_closed_evicted"));
            assertEquals(1, h.counters.get("session_closed_reply_bind_error"));
        }
    }

    @Test void evictsLruIncludingDownlinkActivityAndDoesNotDeleteReplacement() {
        try (Harness h = new Harness()) {
            for (int i = 1; i <= 3; i++) h.manager.receive(client(i), TARGET, bytes(i));
            h.bindings.get(0).setSuccess();
            EmbeddedChannel outbound = h.connect(0);
            ((ByteBuf) outbound.readOutbound()).release();
            h.manager.reply(h.opened.get(0), bytes(8));
            DatagramPacket reply = h.replies.get(0).readOutbound();
            assertEquals(client(1), reply.recipient());
            reply.release();
            // A different client IP avoids the independent per-client limit.
            h.manager.receive(new InetSocketAddress("192.0.2.3", 4), TARGET, bytes(4));
            assertFalse(h.opened.get(0).isClosed());
            assertTrue(h.opened.get(1).isClosed());
            UdpSession old = h.opened.get(1);
            h.manager.receive(client(2), TARGET, bytes(5));
            h.manager.remove(old); // Conditional remove must not remove the replacement.
            assertEquals(3, h.manager.size());
            assertEquals(2, h.counters.get("session_closed_evicted"));
        }
    }

    @Test void perClientLimitIncludesRejectionsAndIdleSweepFreesResources() {
        try (Harness h = new Harness(new UdpRelaySettings(2, 10, 1, 2, 10, 1, 65535, 5, 1))) {
            h.manager.receive(client(1), TARGET, bytes(1));
            h.bindings.get(0).setSuccess();
            ((ByteBuf) h.connect(0).readOutbound()).release();
            ByteBuf rejected = bytes(2);
            h.manager.receive(client(2), TARGET, rejected);
            assertEquals(0, rejected.refCnt());
            assertEquals(1, h.counters.get("drop_per_client_limit"));
            h.advance(3);
            h.manager.sweep();
            assertEquals(0, h.manager.size());
            assertEquals(0, h.pool.size());
            assertEquals(1, h.counters.get("session_closed_idle"));
        }
    }

    @Test void rejectsInvalidAndUnknownFakeIpAndMissingUser() {
        try (Harness h = new Harness()) {
            h.manager.receive(null, TARGET, bytes(1));
            h.manager.receive(client(1), new InetSocketAddress("::1", 443), bytes(2));
            h.manager.receive(client(2), new InetSocketAddress("198.19.255.254", 443), bytes(3));
            h.config.get().inboundConfig().setAnonymousUser(null);
            h.manager.receive(client(3), TARGET, bytes(4));
            assertEquals(2, h.counters.get("drop_invalid"));
            assertEquals(1, h.counters.get("drop_fake_ip_miss"));
            assertEquals(1, h.counters.get("drop_no_user"));
            assertEquals(0, h.opened.size());
        }
    }

    @Test void fakeIpRequestUsesRestoredDomainAndRetainsOriginalAddress() {
        try (Harness h = new Harness()) {
            String fake = FakeIpRegistry.getInstance().getOrAllocate("UDP-TEST.Example.");
            h.manager.receive(client(1), new InetSocketAddress(fake, 443), bytes(1));
            var request = h.opened.get(0).request();
            assertEquals("udp-test.example", request.getTargetHost());
            assertNull(request.getTargetIp());
            assertEquals(fake, request.getOriginalTargetIp());
            assertEquals("FAKE_IP", request.getLogicalTargetSource().name());
        }
    }

    @Test void writeFailureAndUnwritableReleasePayload() {
        try (Harness h = new Harness()) {
            h.manager.receive(client(1), TARGET, bytes(1));
            h.bindings.get(0).setSuccess();
            EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
                @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                    io.netty.util.ReferenceCountUtil.release(msg);
                    promise.setFailure(new IllegalStateException("send failed"));
                }
            });
            h.outbounds.add(channel);
            ByteBuf pending = h.opened.get(0).pending.peek();
            h.connections.get(0).setSuccess(channel);
            assertEquals(0, pending.refCnt());
            assertEquals(0, h.manager.size());
            assertEquals(0, h.pool.size());
        }
        try (Harness h = new Harness()) {
            h.manager.receive(client(1), TARGET, bytes(1));
            EmbeddedChannel channel = h.connect(0);
            channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
            ByteBuf pending = h.opened.get(0).pending.peek();
            h.bindings.get(0).setSuccess();
            assertEquals(0, pending.refCnt());
            assertEquals(1, h.counters.get("drop_unwritable"));
        }
    }

    @Test void outboundFailureAndServerCloseReleaseEachReplyReferenceExactlyOnce() {
        try (Harness h = new Harness()) {
            h.manager.receive(client(1), TARGET, bytes(1));
            h.manager.receive(client(2), TARGET, bytes(2));
            h.connections.get(0).setFailure(new IllegalStateException("connect failed"));
            assertEquals(1, h.counters.get("sessions_rejected"));
            h.manager.close();
            h.manager.close();
            assertEquals(0, h.pool.size());
            assertEquals(0, h.counters.get("reply_sockets"));
            assertEquals(0, h.counters.get("sessions_rejected"));
            assertEquals(1, h.counters.get("session_closed_outbound_error"));
            // The failure rejection entry and the still-connecting session.
            assertEquals(2, h.counters.get("session_closed_server_close"));
        }
    }

    @Test void pendingPayloadIsCompactedOutOfOversizedReadBuffers() {
        try (Harness h = new Harness()) {
            ByteBuf oversized = Unpooled.buffer(65535).writeByte(7);
            h.manager.receive(client(1), TARGET, oversized);
            assertEquals(0, oversized.refCnt());
            ByteBuf queued = h.opened.get(0).pending.peek();
            assertEquals(1, queued.capacity());
            assertEquals(1, h.opened.get(0).pendingBytes);
            h.bindings.get(0).setSuccess();
            ByteBuf sent = h.connect(0).readOutbound();
            assertEquals(7, sent.readByte());
            sent.release();
        }
    }

    @Test void connectTimeoutIsMeasuredFromCreationDespiteRetransmits() {
        try (Harness h = new Harness()) {
            h.manager.receive(client(1), TARGET, bytes(1));
            ByteBuf pending = h.opened.get(0).pending.peek();
            h.advance(1);
            h.manager.receive(client(1), TARGET, bytes(2));
            h.manager.sweep();
            assertFalse(h.opened.get(0).isClosed());
            h.advance(1);
            h.manager.sweep();
            assertTrue(h.opened.get(0).isClosed());
            assertEquals(0, pending.refCnt());
            assertEquals(0, h.pool.size());
            assertEquals(1, h.counters.get("session_closed_connect_timeout"));
            assertEquals(1, h.counters.get("sessions_rejected"));
            assertEquals(0, h.counters.get("sessions_connecting"));
        }
    }

    @Test void setupFailureIsCachedBrieflyThenRetried() {
        try (Harness h = new Harness()) {
            h.manager.receive(client(1), TARGET, bytes(1));
            h.connections.get(0).setFailure(new IllegalStateException("dns failed"));
            ByteBuf retransmit = bytes(2);
            h.manager.receive(client(1), TARGET, retransmit);
            assertEquals(0, retransmit.refCnt());
            assertEquals(1, h.opened.size());
            assertEquals(1, h.replies.size());
            assertEquals(1, h.counters.get("drop_rejected"));
            h.advance(1); // failureCacheSeconds
            h.manager.receive(client(1), TARGET, bytes(3));
            assertEquals(2, h.opened.size());
            assertEquals(UdpSession.State.CONNECTING, h.opened.get(1).state());
        }
    }

    @Test void failureAfterEvictionDoesNotInsertRejection() {
        try (Harness h = new Harness()) {
            h.manager.receive(client(1), TARGET, bytes(1));
            h.opened.get(0).close("evicted");
            h.connections.get(0).setFailure(new IllegalStateException("closed during DNS"));
            assertEquals(0, h.manager.size());
            assertEquals(0, h.counters.get("session_closed_outbound_error"));
        }
    }

    @Test void downlinkBeforeReadyIsCountedAndReleased() {
        try (Harness h = new Harness()) {
            h.manager.receive(client(1), TARGET, bytes(1));
            ByteBuf early = bytes(9);
            h.manager.reply(h.opened.get(0), early);
            assertEquals(0, early.refCnt());
            assertEquals(1, h.counters.get("drop_not_ready"));
        }
    }

    @Test void onlyDirectPolicySupportsUdp() {
        for (RoutePolicy policy : RoutePolicy.values()) {
            assertEquals(policy == RoutePolicy.DIRECT,
                    UdpRouteSupport.supports(ProxyWorkerTestFixtures.route(policy, ProtocolType.NONE, null, null)));
        }
        assertFalse(UdpRouteSupport.supports(null));
    }
}
