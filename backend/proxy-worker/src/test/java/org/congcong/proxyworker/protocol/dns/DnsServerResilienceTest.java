package org.congcong.proxyworker.protocol.dns;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DatagramDnsResponseDecoder;
import io.netty.handler.codec.dns.DnsRawRecord;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.buffer.Unpooled;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.integration.support.FakeDnsServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DnsServerResilienceTest {
    @Test
    void nonHostQuestionsGoToRealDnsAndInvalidPacketsDoNotStopSharedListener() throws Exception {
        for (boolean fakeIpEnabled : new boolean[]{true, false}) {
            try (FakeDnsServer upstream = FakeDnsServer.start("203.0.113.7");
                 DatagramSocket client = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
                client.setSoTimeout(2_000);
                NioEventLoopGroup group = new NioEventLoopGroup(1);
                Channel listener = null;
                try {
                    listener = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                            .handler(new DnsServerInitializer(dnsInbound(upstream.port(), fakeIpEnabled)))
                            .bind("127.0.0.1", 0).sync().channel();
                    InetSocketAddress endpoint = (InetSocketAddress) listener.localAddress();
                    assertEquals(DnsResponseCode.NOERROR, query(client, endpoint, "healthy-resilience.example", 1, null));
                    assertEquals(DnsResponseCode.NOERROR, query(client, endpoint, "_probe.example", 2, "203.0.113.7"));
                    assertEquals(DnsResponseCode.NOERROR, query(client, endpoint, ".", 3, "203.0.113.7"));
                    assertEquals(DnsResponseCode.FORMERR, query(client, endpoint,
                            ("a".repeat(63) + ".").repeat(3) + "b".repeat(62), 6, null));
                    // Too short for a DNS header: the decoder fails before there is a question to answer.
                    client.send(new DatagramPacket(new byte[]{0, 4, 1}, 3, endpoint));
                    assertEquals(DnsResponseCode.NOERROR, query(client, endpoint, "still-healthy-resilience.example", 5, null));
                    assertEquals(fakeIpEnabled ? 2 : 4, upstream.queryCount());
                    assertTrue(listener.isActive());
                } finally {
                    if (listener != null) listener.close().sync();
                    group.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
                }
            }
        }
    }

    @Test
    void requestFailuresAreIsolatedButSocketIoFailuresCloseTheListener() {
        EmbeddedChannel channel = new EmbeddedChannel(DnsExceptionHandler.INSTANCE);
        try {
            channel.pipeline().fireExceptionCaught(new IllegalArgumentException("bad request"));
            channel.pipeline().fireExceptionCaught(new DecoderException(new IOException("bad datagram")));
            assertTrue(channel.isActive());
            channel.pipeline().fireExceptionCaught(new IOException("socket failure"));
            assertFalse(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private InboundConfig dnsInbound(int upstreamPort, boolean fakeIpEnabled) {
        RouteConfig route = new RouteConfig();
        route.setId(735L);
        route.setName("dns-resilience-upstream");
        route.setPolicy(RoutePolicy.OUTBOUND_PROXY);
        route.setOutboundProxyType(ProtocolType.DNS_SERVER);
        route.setOutboundProxyHost("127.0.0.1");
        route.setOutboundProxyPort(upstreamPort);
        UserConfig anonymous = new UserConfig();
        anonymous.setId(736L);
        anonymous.setUsername("dns-resilience-client");
        InboundConfig inbound = new InboundConfig();
        inbound.setId(737L);
        inbound.setProtocol(ProtocolType.DNS_SERVER);
        inbound.setFakeIpEnabled(fakeIpEnabled);
        inbound.setAnonymousUser(anonymous);
        inbound.setDeviceIpMapUser(Map.of());
        inbound.setRoutesMap(Map.of());
        inbound.setDefaultRouteConfig(route);
        return inbound;
    }

    private DnsResponseCode query(DatagramSocket client, InetSocketAddress endpoint, String name, int id,
                                  String expectedAddress) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(id);
        out.writeShort(0x100);
        out.writeShort(1);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0);
        if (!name.equals(".")) {
            for (String label : name.split("\\.")) {
                byte[] encoded = label.getBytes(StandardCharsets.US_ASCII);
                out.writeByte(encoded.length);
                out.write(encoded);
            }
        }
        out.writeByte(0);
        out.writeShort(1); // A
        out.writeShort(1); // IN
        byte[] request = bytes.toByteArray();
        client.send(new DatagramPacket(request, request.length, endpoint));
        DatagramPacket reply = new DatagramPacket(new byte[1024], 1024);
        client.receive(reply);
        assertTrue(reply.getLength() >= 12);
        assertEquals(id, ((reply.getData()[0] & 0xff) << 8) | (reply.getData()[1] & 0xff));
        EmbeddedChannel decoder = new EmbeddedChannel(new DatagramDnsResponseDecoder());
        try {
            decoder.writeInbound(new io.netty.channel.socket.DatagramPacket(
                    Unpooled.wrappedBuffer(Arrays.copyOf(reply.getData(), reply.getLength())),
                    (InetSocketAddress) client.getLocalSocketAddress(), endpoint));
            DatagramDnsResponse response = decoder.readInbound();
            try {
                if (expectedAddress != null) {
                    assertEquals(1, response.count(DnsSection.ANSWER));
                    DnsRawRecord record = response.recordAt(DnsSection.ANSWER);
                    byte[] address = new byte[record.content().readableBytes()];
                    record.content().getBytes(record.content().readerIndex(), address);
                    assertEquals(expectedAddress, InetAddress.getByAddress(address).getHostAddress());
                }
                return response.code();
            } finally {
                response.release();
            }
        } finally {
            decoder.finishAndReleaseAll();
        }
    }
}
