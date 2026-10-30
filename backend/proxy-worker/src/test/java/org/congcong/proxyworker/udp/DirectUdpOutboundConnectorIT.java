package org.congcong.proxyworker.udp;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.epoll.*;
import org.congcong.common.enums.*;
import org.congcong.proxyworker.ProxyWorkerTestFixtures;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.server.AbstractProxyServer.RuntimeConfigSnapshot;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DirectUdpOutboundConnectorIT {
    @Test void literalAndDnsViewRelayLargeDatagramsThenIdleClosesSockets() throws Exception {
        assumeTrue(Epoll.isAvailable());
        for (boolean useDns : new boolean[]{false, true}) {
            EpollEventLoopGroup group = new EpollEventLoopGroup(1);
            EventLoop loop = group.next();
            UdpRelayCounters counters = new UdpRelayCounters();
            try (DatagramSocket echo = new DatagramSocket(0, InetAddress.getLoopbackAddress());
                 DatagramSocket client = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
                echo.setSoTimeout(3000);
                client.setSoTimeout(3000);
                InboundConfig inbound = ProxyWorkerTestFixtures.inbound(ProtocolType.TP_PROXY);
                InboundConfig dns = ProxyWorkerTestFixtures.inbound(ProtocolType.DNS_SERVER);
                dns.getDefaultRouteConfig().setPolicy(RoutePolicy.DNS_REWRITING);
                dns.getDefaultRouteConfig().setOutboundProxyHost("127.0.0.1");
                String target = useDns ? org.congcong.proxyworker.dns.FakeIpRegistry.getInstance()
                        .getOrAllocate("udp-it.example") : "127.0.0.1";
                ReplySocketPool pool = new ReplySocketPool(loop, counters) {
                    @Override protected ChannelFuture bind(InetSocketAddress original) {
                        // The echo fixture already owns T locally. Only this test substitutes a reply source.
                        return new Bootstrap().group(loop).channel(EpollDatagramChannel.class)
                                .handler(new ChannelInboundHandlerAdapter()).bind("127.0.0.1", 0);
                    }
                };
                UdpSessionManager manager = new UdpSessionManager(loop, () -> new RuntimeConfigSnapshot(inbound, dns),
                        new UdpRelaySettings(1, 10, 10, 16, 65536, 1, 65535, 5, 1), new DirectUdpOutboundConnector(), pool, counters);
                byte[] payload = new byte[3000];
                new java.util.Random(42).nextBytes(payload);
                loop.submit(() -> manager.receive((InetSocketAddress) client.getLocalSocketAddress(),
                        new InetSocketAddress(target, echo.getLocalPort()), Unpooled.wrappedBuffer(payload))).sync();
                java.net.DatagramPacket packet = new java.net.DatagramPacket(new byte[65535], 65535);
                echo.receive(packet);
                assertEquals(3000, packet.getLength());
                echo.send(packet);
                java.net.DatagramPacket answer = new java.net.DatagramPacket(new byte[65535], 65535);
                client.receive(answer);
                assertArrayEquals(payload, java.util.Arrays.copyOf(answer.getData(), answer.getLength()));
                assertEquals(1, counters.get("uplink_packets"));
                assertEquals(3000, counters.get("uplink_bytes"));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                while (counters.get("sessions_ready") != 0 && System.nanoTime() < deadline) Thread.sleep(25);
                assertEquals(0, counters.get("sessions_ready"));
                assertEquals(0, counters.get("reply_sockets"));
                assertEquals(1, counters.get("session_closed_idle"));
                loop.submit(manager::close).sync();
            } finally {
                group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
            }
        }
    }

    @Test void rejectsPoolLiteralDnsPoisonAndAbsentDnsView() throws Exception {
        assumeTrue(Epoll.isAvailable());
        EpollEventLoopGroup group = new EpollEventLoopGroup(1);
        try {
            EventLoop loop = group.next();
            InboundConfig inbound = ProxyWorkerTestFixtures.inbound(ProtocolType.TP_PROXY);
            InboundConfig dns = ProxyWorkerTestFixtures.inbound(ProtocolType.DNS_SERVER);
            dns.getDefaultRouteConfig().setPolicy(RoutePolicy.DNS_REWRITING);
            dns.getDefaultRouteConfig().setOutboundProxyHost("198.18.2.2");
            UdpSessionManager manager = new UdpSessionManager(loop, () -> new RuntimeConfigSnapshot(inbound, dns),
                    UdpRelaySettings.fromSystemProperties(), new DirectUdpOutboundConnector(),
                    new ReplySocketPool(loop, new UdpRelayCounters()), new UdpRelayCounters());
            for (String host : new String[]{"198.18.1.1", "poison-udp.example", "no-view.example"}) {
                var request = new org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest(ProtocolType.TP_PROXY,
                        host, 443, inbound.getAnonymousUser(), inbound, null);
                request.setDnsInboundConfig(host.startsWith("no-view") ? null : dns);
                UdpSession session = new UdpSession(manager,
                        new UdpSession.Key(new InetSocketAddress("192.0.2.2", 1234), new InetSocketAddress("203.0.113.1", 443)),
                        request, UdpSession.State.CONNECTING, 1);
                var opened = loop.submit(() -> new DirectUdpOutboundConnector().open(session, loop)).get();
                assertTrue(opened.await(3, TimeUnit.SECONDS));
                assertFalse(opened.isSuccess());
                loop.submit(() -> session.close("server_close")).sync();
            }
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }
}
