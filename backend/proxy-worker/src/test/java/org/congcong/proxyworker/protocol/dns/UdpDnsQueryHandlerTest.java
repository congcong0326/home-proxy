package org.congcong.proxyworker.protocol.dns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsSection;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.ProxyWorkerTestFixtures;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.DnsProxyContext;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.junit.jupiter.api.Test;

class UdpDnsQueryHandlerTest {
    @Test
    void convertsDatagramDnsQueryToDnsTunnelRequest() {
        UserConfig user = ProxyWorkerTestFixtures.user(9L, "dns-device", null, "192.168.1.30");
        InboundConfig inbound = ProxyWorkerTestFixtures.deviceInbound(ProtocolType.DNS_SERVER, user);
        EmbeddedChannel channel = new EmbeddedChannel(new UdpDnsQueryHandler());
        ChannelAttributes.setInboundConfig(channel, inbound);

        DatagramDnsQuery query = new DatagramDnsQuery(
                ProxyWorkerTestFixtures.socket("192.168.1.30", 5353),
                ProxyWorkerTestFixtures.socket("192.168.1.1", 53),
                0x1234);
        query.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("example.com.", DnsRecordType.A));

        channel.writeInbound(query);

        ProxyTunnelRequest request = channel.readInbound();
        DnsProxyContext dnsCtx = (DnsProxyContext) request.getProtocolAttachment();
        assertEquals(ProtocolType.DNS_SERVER, request.getProtocolType());
        assertEquals("example.com.", request.getTargetHost());
        assertEquals(53, request.getTargetPort());
        assertSame(user, request.getUser());
        assertSame(inbound, request.getInboundConfig());
        assertEquals(0x1234, dnsCtx.getId());
        assertEquals("example.com.", dnsCtx.getQName());
        assertEquals(DnsRecordType.A, dnsCtx.getQType());
        assertEquals("192.168.1.30", dnsCtx.getClient().getAddress().getHostAddress());
        assertEquals(user.getId(), request.getProxyContext().getUserId());
        assertEquals(user.getUsername(), request.getProxyContext().getUserName());
        assertTrue(request.getProxyContext().getBytesIn() > 0);

        channel.finishAndReleaseAll();
    }
}
