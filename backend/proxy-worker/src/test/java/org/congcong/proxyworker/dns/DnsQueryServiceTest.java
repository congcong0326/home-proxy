package org.congcong.proxyworker.dns;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponseCode;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.common.enums.RouteConditionType;
import org.congcong.common.enums.MatchOp;
import org.congcong.common.dto.RouteRule;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DnsQueryServiceTest {
    @Test
    void nonHostNamesStillApplyBlockingAndDnsRewritingBeforeForwarding() throws Exception {
        RouteConfig route = forward(405L, 53);
        InboundConfig dns = dnsInbound(route);
        dns.setFakeIpEnabled(true);
        DnsQueryContext view = new DnsQueryContext("192.0.2.40", dns, "non-host-policy-v1");
        DnsQueryService service = DnsQueryService.getInstance();
        route.setPolicy(RoutePolicy.BLOCK);
        assertEquals(DnsResult.blocked(route.getId()),
                service.query("_probe.example", DnsRecordType.A, QueryMode.CLIENT_FAKE, view, null).get());
        route.setPolicy(RoutePolicy.DNS_REWRITING);
        route.setOutboundProxyHost("203.0.113.8");
        DnsResult result = service.query(".", DnsRecordType.A, QueryMode.CLIENT_FAKE, view, null).get();
        assertEquals(List.of("203.0.113.8"), result.addresses());
        assertFalse(result.fake());
    }

    @Test
    void nonHostNamesUseRealAAndAaaaAnswersEvenWithFakeIpEnabled() throws Exception {
        try (DatagramSocket upstream = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            upstream.setSoTimeout(3_000);
            NioEventLoopGroup group = new NioEventLoopGroup(1);
            try {
                InboundConfig dns = dnsInbound(forward(404L, upstream.getLocalPort()));
                dns.setFakeIpEnabled(true);
                DnsQueryContext view = new DnsQueryContext("192.0.2.40", dns, "non-host-v1");
                DnsQueryService service = DnsQueryService.getInstance();
                var loop = group.next();
                for (String name : new String[]{"_probe.example", ".", "-probe.example"}) {
                    for (DnsRecordType type : new DnsRecordType[]{DnsRecordType.A, DnsRecordType.AAAA}) {
                        var query = service.query(name, type, QueryMode.CLIENT_FAKE, view, loop);
                        DatagramPacket packet = new DatagramPacket(new byte[512], 512);
                        upstream.receive(packet);
                        byte[] address = InetAddress.getByName(type == DnsRecordType.A
                                ? "203.0.113.7" : "2001:db8::7").getAddress();
                        byte[] response = addressResponse(Arrays.copyOf(packet.getData(), packet.getLength()), type, address);
                        upstream.send(new DatagramPacket(response, response.length, packet.getSocketAddress()));
                        DnsResult result = query.get(2, TimeUnit.SECONDS);
                        assertEquals(DnsResponseCode.NOERROR, result.code());
                        assertFalse(result.fake());
                        assertArrayEquals(address, InetAddress.getByName(result.addresses().get(0)).getAddress());
                    }
                }
                assertEquals(0, service.pendingCount(loop).get(2, TimeUnit.SECONDS));
            } finally {
                group.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
            }
        }
    }

    @Test
    void hostnameUpstreamSupportsAddressQueriesOnlyWhenFakeIpIsDisabled() throws Exception {
        try (DatagramSocket upstream = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            upstream.setSoTimeout(3_000);
            NioEventLoopGroup group = new NioEventLoopGroup(1);
            try {
                RouteConfig route = forward(401L, upstream.getLocalPort());
                route.setOutboundProxyHost("localhost");
                InboundConfig dns = dnsInbound(route);
                DnsQueryService service = DnsQueryService.getInstance();
                var loop = group.next();
                for (DnsRecordType type : new DnsRecordType[]{DnsRecordType.A, DnsRecordType.AAAA}) {
                    var query = service.query("hostname-upstream-test.example", type, QueryMode.CLIENT_FAKE,
                            new DnsQueryContext("192.0.2.40", dns, "hostname-v1"), loop);
                    DatagramPacket packet = new DatagramPacket(new byte[512], 512);
                    upstream.receive(packet);
                    byte[] address = InetAddress.getByName(type == DnsRecordType.A
                            ? "203.0.113.7" : "2001:db8::7").getAddress();
                    byte[] response = addressResponse(Arrays.copyOf(packet.getData(), packet.getLength()), type, address);
                    upstream.send(new DatagramPacket(response, response.length, packet.getSocketAddress()));
                    DnsResult result = query.get(3, TimeUnit.SECONDS);
                    assertEquals(DnsResponseCode.NOERROR, result.code());
                    assertFalse(result.fake());
                    assertArrayEquals(address, InetAddress.getByName(result.addresses().get(0)).getAddress());
                }
                dns.setFakeIpEnabled(true);
                assertThrows(ExecutionException.class, () -> service.query("hostname-upstream-test.example",
                        DnsRecordType.A, QueryMode.OUTBOUND_REAL,
                        new DnsQueryContext("192.0.2.40", dns, "hostname-v2"), loop).get(2, TimeUnit.SECONDS));
                assertEquals(0, service.pendingCount(loop).get(2, TimeUnit.SECONDS));
            } finally {
                group.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
            }
        }
    }

    @Test
    void malformedCnameReleasesSharedFlightAndAllSubscribers() throws Exception {
        try (DatagramSocket upstream = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            upstream.setSoTimeout(3_000);
            NioEventLoopGroup group = new NioEventLoopGroup(1);
            try {
                InboundConfig dns = dnsInbound(forward(402L, upstream.getLocalPort()));
                DnsQueryContext view = new DnsQueryContext("192.0.2.40", dns, "malformed-v1");
                DnsQueryService service = DnsQueryService.getInstance();
                var loop = group.next();
                var first = service.query("shared-malformed-cname.test", DnsRecordType.A, QueryMode.OUTBOUND_REAL, view, loop);
                var second = service.query("shared-malformed-cname.test", DnsRecordType.A, QueryMode.OUTBOUND_REAL, view, loop);
                DatagramPacket packet = new DatagramPacket(new byte[512], 512);
                upstream.receive(packet);
                // Four maximum-length labels exceed the 255-byte DNS wire limit.
                byte[] target = new byte[257];
                Arrays.fill(target, (byte) 'a');
                for (int index = 0; index < 256; index += 64) target[index] = 63;
                target[256] = 0;
                byte[] response = addressResponse(Arrays.copyOf(packet.getData(), packet.getLength()),
                        DnsRecordType.CNAME, target);
                upstream.send(new DatagramPacket(response, response.length, packet.getSocketAddress()));
                assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS));
                assertThrows(ExecutionException.class, () -> second.get(2, TimeUnit.SECONDS));
                assertEquals(0, service.pendingCount(loop).get(2, TimeUnit.SECONDS));
                assertEquals(0, service.inFlightCount());
            } finally {
                group.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
            }
        }
    }

    private InboundConfig dnsInbound(RouteConfig route) {
        UserConfig anonymous = new UserConfig();
        anonymous.setId(403L);
        InboundConfig dns = new InboundConfig();
        dns.setProtocol(ProtocolType.DNS_SERVER);
        dns.setAnonymousUser(anonymous);
        dns.setDeviceIpMapUser(Map.of());
        dns.setRoutesMap(Map.of());
        dns.setDefaultRouteConfig(route);
        dns.setFakeIpEnabled(false);
        return dns;
    }

    private byte[] addressResponse(byte[] request, DnsRecordType type, byte[] address) {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        response.writeBytes(Arrays.copyOf(request, 2));
        response.writeBytes(new byte[]{(byte) 0x81, (byte) 0x80, 0, 1, 0, 1, 0, 0, 0, 0});
        response.writeBytes(Arrays.copyOfRange(request, 12, request.length));
        response.writeBytes(new byte[]{(byte) 0xc0, 12, (byte) (type.intValue() >>> 8), (byte) type.intValue(),
                0, 1, 0, 0, 0, 2, (byte) (address.length >>> 8), (byte) address.length});
        response.writeBytes(address);
        return response.toByteArray();
    }

    @Test
    void cancellingLastSubscriberReleasesSharedUpstreamRequest() throws Exception {
        try (DatagramSocket upstream = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            NioEventLoopGroup group = new NioEventLoopGroup(1);
            try {
                RouteConfig route = forward(301L, upstream.getLocalPort());
                UserConfig anonymous = new UserConfig();
                anonymous.setId(302L);
                InboundConfig dns = new InboundConfig();
                dns.setProtocol(ProtocolType.DNS_SERVER);
                dns.setAnonymousUser(anonymous);
                dns.setDeviceIpMapUser(Map.of());
                dns.setRoutesMap(Map.of());
                dns.setDefaultRouteConfig(route);
                DnsQueryContext context = new DnsQueryContext("192.0.2.30", dns, "cancel-v1");
                DnsQueryService service = DnsQueryService.getInstance();
                var loop = group.next();
                var first = service.query("shared-cancel-test.example", DnsRecordType.A,
                        QueryMode.OUTBOUND_REAL, context, loop);
                var second = service.query("shared-cancel-test.example", DnsRecordType.A,
                        QueryMode.OUTBOUND_REAL, context, loop);
                upstream.setSoTimeout(3_000);
                upstream.receive(new DatagramPacket(new byte[512], 512));
                assertEquals(1, service.pendingCount(loop).get(2, TimeUnit.SECONDS));
                assertTrue(first.cancel(false));
                assertEquals(1, service.pendingCount(loop).get(2, TimeUnit.SECONDS));
                assertTrue(second.cancel(false));
                assertEquals(0, service.pendingCount(loop).get(2, TimeUnit.SECONDS));
                assertEquals(0, service.inFlightCount());
            } finally {
                group.shutdownGracefully().sync();
            }
        }
    }

    @Test
    void cacheSeparatesDeviceRoutesAndConfigurationVersions() throws Exception {
        AtomicInteger deviceQueries = new AtomicInteger();
        AtomicInteger anonymousQueries = new AtomicInteger();
        try (DatagramSocket deviceUpstream = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             DatagramSocket anonymousUpstream = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            Thread first = new Thread(() -> serve(deviceUpstream, deviceQueries,
                    new byte[]{(byte) 203, 0, 113, 7}));
            Thread second = new Thread(() -> serve(anonymousUpstream, anonymousQueries,
                    new byte[]{(byte) 203, 0, 113, 8}));
            first.setDaemon(true);
            second.setDaemon(true);
            first.start();
            second.start();
            NioEventLoopGroup group = new NioEventLoopGroup(1);
            try {
                RouteConfig deviceRoute = forward(201L, deviceUpstream.getLocalPort());
                RouteRule rule = new RouteRule();
                rule.setConditionType(RouteConditionType.DOMAIN);
                rule.setOp(MatchOp.IN);
                rule.setValue("split-cache-test.example");
                deviceRoute.setRules(List.of(rule));
                RouteConfig anonymousRoute = forward(202L, anonymousUpstream.getLocalPort());
                UserConfig device = new UserConfig();
                device.setId(91L);
                UserConfig anonymous = new UserConfig();
                anonymous.setId(92L);
                InboundConfig dns = new InboundConfig();
                dns.setProtocol(ProtocolType.DNS_SERVER);
                dns.setAnonymousUser(anonymous);
                dns.setDeviceIpMapUser(Map.of("192.0.2.10", device));
                dns.setRoutesMap(Map.of(device.getId(), List.of(deviceRoute)));
                dns.setDefaultRouteConfig(anonymousRoute);
                DnsQueryService service = DnsQueryService.getInstance();
                var deviceView = new DnsQueryContext("192.0.2.10", dns, "v1");
                var anonymousView = new DnsQueryContext("192.0.2.20", dns, "v1");
                assertEquals("203.0.113.7", service.query("split-cache-test.example", DnsRecordType.A,
                        QueryMode.OUTBOUND_REAL, deviceView, group.next()).get(4, TimeUnit.SECONDS).addresses().get(0));
                assertEquals("203.0.113.8", service.query("split-cache-test.example", DnsRecordType.A,
                        QueryMode.OUTBOUND_REAL, anonymousView, group.next()).get(4, TimeUnit.SECONDS).addresses().get(0));
                service.query("split-cache-test.example", DnsRecordType.A,
                        QueryMode.OUTBOUND_REAL, deviceView, group.next()).get(4, TimeUnit.SECONDS);
                assertEquals(1, deviceQueries.get());
                assertEquals(1, anonymousQueries.get());
                service.query("split-cache-test.example", DnsRecordType.A,
                        QueryMode.OUTBOUND_REAL, new DnsQueryContext("192.0.2.10", dns, "v2"),
                        group.next()).get(4, TimeUnit.SECONDS);
                assertEquals(2, deviceQueries.get());
            } finally {
                group.shutdownGracefully().sync();
            }
        }
    }

    private RouteConfig forward(long id, int port) {
        RouteConfig route = new RouteConfig();
        route.setId(id);
        route.setPolicy(RoutePolicy.OUTBOUND_PROXY);
        route.setOutboundProxyType(ProtocolType.DNS_SERVER);
        route.setOutboundProxyHost("127.0.0.1");
        route.setOutboundProxyPort(port);
        return route;
    }

    @Test
    void realQueriesUseSelectedUpstreamAndMergeConcurrentCacheMisses() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        try (DatagramSocket upstream = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            Thread responder = new Thread(() -> serve(upstream, queries));
            responder.setDaemon(true);
            responder.start();
            NioEventLoopGroup group = new NioEventLoopGroup(1);
            try {
                RouteConfig route = new RouteConfig();
                route.setId(101L);
                route.setPolicy(RoutePolicy.OUTBOUND_PROXY);
                route.setOutboundProxyType(ProtocolType.DNS_SERVER);
                route.setOutboundProxyHost("127.0.0.1");
                route.setOutboundProxyPort(upstream.getLocalPort());
                UserConfig anonymous = new UserConfig();
                anonymous.setId(22L);
                InboundConfig dns = new InboundConfig();
                dns.setProtocol(ProtocolType.DNS_SERVER);
                dns.setAnonymousUser(anonymous);
                dns.setDeviceIpMapUser(Map.of());
                dns.setRoutesMap(Map.of());
                dns.setDefaultRouteConfig(route);
                dns.setFakeIpEnabled(true);
                DnsQueryContext context = new DnsQueryContext("192.0.2.1", dns, "test-version");
                DnsQueryService service = DnsQueryService.getInstance();
                var first = service.query("unique-real-test.example", DnsRecordType.A,
                        QueryMode.OUTBOUND_REAL, context, group.next());
                var second = service.query("unique-real-test.example", DnsRecordType.A,
                        QueryMode.OUTBOUND_REAL, context, group.next());
                assertEquals("203.0.113.7", first.get(4, TimeUnit.SECONDS).addresses().get(0));
                assertEquals(first.get().addresses(), second.get(4, TimeUnit.SECONDS).addresses());
                assertEquals(1, queries.get());
                assertEquals(DnsResponseCode.NOERROR, service.query("unique-real-test.example", DnsRecordType.A,
                        QueryMode.OUTBOUND_REAL, context, group.next()).get(4, TimeUnit.SECONDS).code());
                assertEquals(1, queries.get());
                Thread.sleep(2_100);
                service.query("unique-real-test.example", DnsRecordType.A, QueryMode.OUTBOUND_REAL,
                        context, group.next()).get(4, TimeUnit.SECONDS);
                assertEquals(2, queries.get());
            } finally {
                group.shutdownGracefully().sync();
            }
        }
    }

    private void serve(DatagramSocket server, AtomicInteger count) {
        serve(server, count, new byte[]{(byte) 203, 0, 113, 7});
    }

    private void serve(DatagramSocket server, AtomicInteger count, byte[] address) {
        byte[] buffer = new byte[512];
        while (!server.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                server.receive(packet);
                count.incrementAndGet();
                byte[] request = Arrays.copyOf(packet.getData(), packet.getLength());
                int end = 12;
                while (end < request.length && request[end] != 0) end += (request[end] & 0xff) + 1;
                end += 5; // zero label and QTYPE/QCLASS
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                response.write(request, 0, 2); // ID
                response.write(new byte[]{(byte) 0x81, (byte) 0x80, 0, 1, 0, 1, 0, 0, 0, 0});
                response.write(request, 12, end - 12);
                response.write(new byte[]{(byte) 0xc0, 12, 0, 1, 0, 1, 0, 0, 0, 2, 0, 4});
                response.write(address);
                byte[] bytes = response.toByteArray();
                server.send(new DatagramPacket(bytes, bytes.length, packet.getSocketAddress()));
            } catch (Exception error) {
                if (!server.isClosed()) throw new RuntimeException(error);
            }
        }
    }
}
