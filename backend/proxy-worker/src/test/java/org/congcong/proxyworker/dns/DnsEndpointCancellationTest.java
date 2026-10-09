package org.congcong.proxyworker.dns;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.DnsRecordType;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.outbound.OutboundEndpointResolver;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DnsEndpointCancellationTest {
    @Test
    void disconnectCancelsActiveASubscription() throws Exception {
        assertDisconnectCancelsLookup(false);
    }

    @Test
    void disconnectCancelsActiveAaaaSubscription() throws Exception {
        assertDisconnectCancelsLookup(true);
    }

    private void assertDisconnectCancelsLookup(boolean retryAaaa) throws Exception {
        try (DatagramSocket upstream = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            upstream.setSoTimeout(3_000);
            NioEventLoopGroup group = new NioEventLoopGroup(1);
            Channel inbound = null;
            try {
                var loop = group.next();
                inbound = new Bootstrap().group(loop).channel(NioDatagramChannel.class)
                        .handler(new ChannelInboundHandlerAdapter()).bind("127.0.0.1", 0).sync().channel();
                InboundConfig dns = dnsInbound(upstream.getLocalPort());
                String host = retryAaaa ? "cancel-proxy-aaaa.test" : "cancel-proxy-a.test";
                ProxyTunnelRequest request = new ProxyTunnelRequest(ProtocolType.SOCKS5, "example.com", 443,
                        null, new InboundConfig(), null);
                request.setDnsInboundConfig(dns);
                org.congcong.common.dto.ProxyContext proxyContext = new org.congcong.common.dto.ProxyContext();
                proxyContext.setClientIp("192.0.2.50");
                request.setProxyContext(proxyContext);
                AtomicInteger connections = new AtomicInteger();
                Channel currentInbound = inbound;
                var relay = loop.<Channel>newPromise();
                var connection = OutboundEndpointResolver.connect(inbound, request, relay, host, 443, endpoint -> {
                    connections.incrementAndGet();
                    return currentInbound.newSucceededFuture();
                });
                DatagramPacket packet = receive(upstream);
                assertEquals(DnsRecordType.A.intValue(), questionType(packet));
                if (retryAaaa) {
                    // NODATA for A causes an AAAA subscription to replace it.
                    byte[] empty = Arrays.copyOf(packet.getData(), packet.getLength());
                    empty[2] = (byte) 0x81;
                    empty[3] = (byte) 0x80;
                    upstream.send(new DatagramPacket(empty, empty.length, packet.getSocketAddress()));
                    packet = receive(upstream);
                    assertEquals(DnsRecordType.AAAA.intValue(), questionType(packet));
                }

                DnsQueryService service = DnsQueryService.getInstance();
                assertEquals(1, service.pendingCount(loop).get(2, TimeUnit.SECONDS));
                inbound.close().sync();
                assertTrue(connection.await(2, TimeUnit.SECONDS));
                assertFalse(connection.isSuccess());
                assertEquals(0, service.pendingCount(loop).get(2, TimeUnit.SECONDS));
                assertEquals(0, service.inFlightCount());
                assertEquals(0, connections.get());

                // A late answer must not populate the cache after disconnect.
                byte[] late = answer(packet, retryAaaa);
                upstream.send(new DatagramPacket(late, late.length, packet.getSocketAddress()));
                DnsRecordType type = retryAaaa ? DnsRecordType.AAAA : DnsRecordType.A;
                var next = service.query(host, type, QueryMode.OUTBOUND_REAL,
                        new DnsQueryContext("192.0.2.50", dns, dns.getConfigVersion()), loop);
                try {
                    assertEquals(type.intValue(), questionType(receive(upstream)));
                } finally {
                    next.cancel(false);
                }
                assertEquals(0, service.pendingCount(loop).get(2, TimeUnit.SECONDS));
            } finally {
                if (inbound != null) inbound.close().sync();
                group.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
            }
        }
    }

    private InboundConfig dnsInbound(int port) {
        RouteConfig route = new RouteConfig();
        route.setId(501L);
        route.setPolicy(RoutePolicy.OUTBOUND_PROXY);
        route.setOutboundProxyType(ProtocolType.DNS_SERVER);
        route.setOutboundProxyHost("127.0.0.1");
        route.setOutboundProxyPort(port);
        UserConfig anonymous = new UserConfig();
        anonymous.setId(502L);
        InboundConfig dns = new InboundConfig();
        dns.setProtocol(ProtocolType.DNS_SERVER);
        dns.setConfigVersion("endpoint-cancel-v1");
        dns.setAnonymousUser(anonymous);
        dns.setDeviceIpMapUser(Map.of());
        dns.setRoutesMap(Map.of());
        dns.setDefaultRouteConfig(route);
        return dns;
    }

    private DatagramPacket receive(DatagramSocket socket) throws Exception {
        DatagramPacket packet = new DatagramPacket(new byte[512], 512);
        socket.receive(packet);
        return packet;
    }

    private int questionType(DatagramPacket packet) {
        return ((packet.getData()[packet.getLength() - 4] & 0xff) << 8)
                | (packet.getData()[packet.getLength() - 3] & 0xff);
    }

    private byte[] answer(DatagramPacket packet, boolean ipv6) throws Exception {
        byte[] address = InetAddress.getByName(ipv6 ? "2001:db8::7" : "203.0.113.7").getAddress();
        byte[] response = Arrays.copyOf(packet.getData(), packet.getLength() + 12 + address.length);
        response[2] = (byte) 0x81;
        response[3] = (byte) 0x80;
        response[7] = 1;
        int offset = packet.getLength();
        System.arraycopy(new byte[]{(byte) 0xc0, 12, 0, (byte) (ipv6 ? 28 : 1), 0, 1,
                0, 0, 0, 30, 0, (byte) address.length}, 0, response, offset, 12);
        System.arraycopy(address, 0, response, offset + 12, address.length);
        return response;
    }
}
