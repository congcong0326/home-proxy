package org.congcong.proxyworker.protocol.dns;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRawRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.integration.support.FakeDnsServer;
import org.congcong.proxyworker.server.tunnel.DnsProxyContext;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FakeIpDnsAnswerHandlerTest {
    @Test
    void completedAndCachedRealAnswersRemoveChannelCloseListeners() throws Exception {
        try (FakeDnsServer upstream = FakeDnsServer.start("203.0.113.7");
             DatagramSocket client = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            client.setSoTimeout(3_000);
            NioEventLoopGroup group = new NioEventLoopGroup(1);
            Channel channel = null;
            try {
                channel = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                        .handler(new ChannelInitializer<Channel>() {
                            @Override protected void initChannel(Channel ch) {
                                ch.pipeline().addLast(new FakeIpDnsAnswerHandler(), new DatagramDnsResponseEncoder());
                            }
                        }).bind("127.0.0.1", 0).sync().channel();
                var listeners = DefaultPromise.class.getDeclaredField("listeners");
                listeners.setAccessible(true);
                Object initial = listeners.get(channel.closeFuture());
                Channel current = channel;
                for (int i = 0; i < 3; i++) {
                    ProxyTunnelRequest template = request("cached-listener-cleanup.test", DnsRecordType.A, false);
                    RouteConfig route = template.getRouteConfig();
                    route.setOutboundProxyHost("127.0.0.1");
                    route.setOutboundProxyPort(upstream.port());
                    ProxyTunnelRequest query = new ProxyTunnelRequest(ProtocolType.DNS_SERVER,
                            template.getTargetHost(), 53, null, template.getInboundConfig(),
                            new DnsProxyContext(31, template.getTargetHost(), DnsRecordType.A,
                                    (InetSocketAddress) client.getLocalSocketAddress()));
                    query.setRouteConfig(route);
                    current.eventLoop().execute(() -> current.pipeline().fireChannelRead(query));
                    DatagramPacket response = new DatagramPacket(new byte[512], 512);
                    client.receive(response);
                    assertEquals(0, response.getData()[3] & 0x0f); // NOERROR
                    assertSame(initial, current.eventLoop().submit(() -> listeners.get(current.closeFuture()))
                            .get(2, TimeUnit.SECONDS));
                }
                assertEquals(1, upstream.queryCount());
            } finally {
                if (channel != null) channel.close().sync();
                group.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
            }
        }
    }

    @Test
    void completedQueriesDoNotAccumulateChannelCloseListeners() throws Exception {
        EmbeddedChannel channel = new DnsChannel(new FakeIpDnsAnswerHandler());
        var listeners = DefaultPromise.class.getDeclaredField("listeners");
        listeners.setAccessible(true);
        Object initial = listeners.get(channel.closeFuture());
        try {
            for (int i = 0; i < 100; i++) {
                // Includes immediately completed Fake IP answers and failed upstream lookups.
                channel.writeInbound(request("listener-cleanup-test.example", DnsRecordType.A, i % 2 == 0));
                DatagramDnsResponse response = channel.readOutbound();
                assertNotNull(response);
                response.release();
                assertSame(initial, listeners.get(channel.closeFuture()));
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void returnsStableOneSecondAAndEmptyAaaaWithoutUpstream() {
        EmbeddedChannel channel = new DnsChannel(new FakeIpDnsAnswerHandler());
        channel.writeInbound(request("fake-ip-test.example", DnsRecordType.A, true));
        channel.writeInbound(request("FAKE-IP-TEST.EXAMPLE.", DnsRecordType.A, true));
        channel.writeInbound(request("fake-ip-test.example", DnsRecordType.AAAA, true));
        DatagramDnsResponse firstResponse = channel.readOutbound();
        DatagramDnsResponse secondResponse = channel.readOutbound();
        DatagramDnsResponse thirdResponse = channel.readOutbound();
        try {
            DnsRawRecord first = firstResponse.recordAt(DnsSection.ANSWER);
            DnsRawRecord second = secondResponse.recordAt(DnsSection.ANSWER);
            assertEquals(1, first.timeToLive());
            assertEquals(first.content(), second.content());
            assertEquals(0, thirdResponse.count(DnsSection.ANSWER));
            for (DatagramDnsResponse response : new DatagramDnsResponse[]{firstResponse, secondResponse, thirdResponse}) {
                assertEquals(31, response.id());
                assertNotNull((DnsQuestion) response.recordAt(DnsSection.QUESTION));
            }
        } finally {
            firstResponse.release();
            secondResponse.release();
            thirdResponse.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void passesNonAddressQuestionsToLegacyPath() {
        EmbeddedChannel channel = new DnsChannel(new FakeIpDnsAnswerHandler());
        ProxyTunnelRequest request = request("example.com", DnsRecordType.MX, false);
        channel.writeInbound(request);
        assertSame(request, channel.readInbound());
        channel.finishAndReleaseAll();
    }

    private ProxyTunnelRequest request(String name, DnsRecordType type, boolean enabled) {
        InboundConfig inbound = new InboundConfig();
        inbound.setProtocol(ProtocolType.DNS_SERVER);
        inbound.setFakeIpEnabled(enabled);
        RouteConfig route = new RouteConfig();
        route.setPolicy(RoutePolicy.OUTBOUND_PROXY);
        route.setOutboundProxyType(ProtocolType.DNS_SERVER);
        UserConfig anonymous = new UserConfig();
        anonymous.setId(1L);
        inbound.setAnonymousUser(anonymous);
        inbound.setDeviceIpMapUser(Map.of());
        inbound.setRoutesMap(Map.of());
        inbound.setDefaultRouteConfig(route);
        ProxyTunnelRequest request = new ProxyTunnelRequest(ProtocolType.DNS_SERVER, name, 53, null,
                inbound, new DnsProxyContext(31, name, type, new InetSocketAddress("127.0.0.1", 5050)));
        request.setRouteConfig(route);
        return request;
    }

    private static final class DnsChannel extends EmbeddedChannel {
        private DnsChannel(FakeIpDnsAnswerHandler handler) {
            super(handler);
        }

        @Override
        protected SocketAddress localAddress0() {
            return new InetSocketAddress("127.0.0.1", 5300);
        }
    }
}
