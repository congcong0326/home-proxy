package org.congcong.proxyworker.dns;

import io.netty.channel.nio.NioEventLoopGroup;
import org.congcong.common.enums.*;
import org.congcong.proxyworker.ProxyWorkerTestFixtures;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class OutboundAddressQueryTest {
    @Test void emptyAAdvancesToAaaaAndCancellationReleasesEitherActiveSubscription() throws Exception {
        for (String outcome : new String[]{"answer", "cancel-a", "cancel-aaaa"}) {
            NioEventLoopGroup group = new NioEventLoopGroup(1);
            try (DatagramSocket dnsServer = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
                dnsServer.setSoTimeout(2000);
                var dns = ProxyWorkerTestFixtures.inbound(ProtocolType.DNS_SERVER);
                dns.setDefaultRouteConfig(ProxyWorkerTestFixtures.route(RoutePolicy.OUTBOUND_PROXY,
                        ProtocolType.DNS_SERVER, "127.0.0.1", dnsServer.getLocalPort()));
                var loop = group.next();
                var lookup = OutboundAddressQuery.queryAddresses("udp-address-" + outcome + ".example",
                        new DnsQueryContext("192.0.2.2", dns, outcome), loop);
                DatagramPacket packet = new DatagramPacket(new byte[512], 512);
                dnsServer.receive(packet);
                byte[] query = Arrays.copyOf(packet.getData(), packet.getLength());
                assertEquals(1, query[query.length - 3]); // A
                if (!outcome.equals("cancel-a")) {
                    query[2] = (byte) 0x81;
                    query[3] = (byte) 0x80;
                    dnsServer.send(new DatagramPacket(query, query.length, packet.getSocketAddress()));
                    packet.setLength(512);
                    dnsServer.receive(packet);
                    query = Arrays.copyOf(packet.getData(), packet.getLength());
                    assertEquals(28, query[query.length - 3]); // AAAA
                }
                if (outcome.equals("answer")) {
                    byte[] response = Arrays.copyOf(query, query.length + 28);
                    response[2] = (byte) 0x81;
                    response[3] = (byte) 0x80;
                    response[7] = 1;
                    byte[] record = {(byte) 0xc0, 12, 0, 28, 0, 1, 0, 0, 0, 0, 0, 16};
                    System.arraycopy(record, 0, response, query.length, record.length);
                    response[response.length - 1] = 1;
                    dnsServer.send(new DatagramPacket(response, response.length, packet.getSocketAddress()));
                    assertEquals("0:0:0:0:0:0:0:1", InetAddress.getByName(lookup.get(2, TimeUnit.SECONDS)
                            .addresses().get(0)).getHostAddress());
                } else {
                    assertTrue(lookup.cancel(false));
                }
                assertEquals(0, DnsQueryService.getInstance().pendingCount(loop).get(2, TimeUnit.SECONDS));
                assertEquals(0, DnsQueryService.getInstance().inFlightCount());
            } finally {
                group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
            }
        }
    }
}
