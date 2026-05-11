package org.congcong.proxyworker.protocol.dns;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DnsRawRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.util.NetUtil;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.proxyworker.AddressedEmbeddedChannel;
import org.congcong.proxyworker.ProxyWorkerTestFixtures;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.junit.jupiter.api.Test;

class DnsRewritingProtocolStrategyTest {
    @Test
    void returnsNoErrorARecordForValidIpv4Rewrite() {
        TestInbound inbound = newInbound();
        ProxyTunnelRequest request = dnsRewriteRequest(inbound.inbound, inbound.user, "example.com.", DnsRecordType.A, "10.0.0.8");

        new DnsRewritingProtocolStrategy().onConnectSuccess(inbound.ctx, null, request);

        DatagramDnsResponse response = inbound.channel.readOutbound();
        try {
            assertEquals(0x4444, response.id());
            assertEquals(DnsResponseCode.NOERROR, response.code());
            assertEquals(inbound.client, response.recipient());
            assertEquals(1, response.count(DnsSection.QUESTION));
            assertEquals(1, response.count(DnsSection.ANSWER));
            DnsRawRecord answer = response.recordAt(DnsSection.ANSWER);
            assertEquals(DnsRecordType.A, answer.type());
            byte[] bytes = new byte[answer.content().readableBytes()];
            answer.content().getBytes(answer.content().readerIndex(), bytes);
            assertEquals("10.0.0.8", NetUtil.bytesToIpAddress(bytes));
        } finally {
            response.release();
            inbound.channel.finishAndReleaseAll();
        }
    }

    @Test
    void returnsServfailForInvalidRewriteIp() {
        TestInbound inbound = newInbound();
        ProxyTunnelRequest request = dnsRewriteRequest(inbound.inbound, inbound.user, "example.com.", DnsRecordType.A, "not-an-ip");

        new DnsRewritingProtocolStrategy().onConnectSuccess(inbound.ctx, null, request);

        DatagramDnsResponse response = inbound.channel.readOutbound();
        try {
            assertEquals(DnsResponseCode.SERVFAIL, response.code());
            assertEquals(0, response.count(DnsSection.ANSWER));
        } finally {
            response.release();
            inbound.channel.finishAndReleaseAll();
        }
    }

    @Test
    void doesNotForgeARecordForAaaaQuery() {
        TestInbound inbound = newInbound();
        ProxyTunnelRequest request = dnsRewriteRequest(inbound.inbound, inbound.user, "example.com.", DnsRecordType.AAAA, "10.0.0.8");

        new DnsRewritingProtocolStrategy().onConnectSuccess(inbound.ctx, null, request);

        DatagramDnsResponse response = inbound.channel.readOutbound();
        try {
            assertEquals(DnsResponseCode.NOERROR, response.code());
            assertEquals(0, response.count(DnsSection.ANSWER));
        } finally {
            response.release();
            inbound.channel.finishAndReleaseAll();
        }
    }

    private ProxyTunnelRequest dnsRewriteRequest(InboundConfig inbound,
                                                 UserConfig user,
                                                 String qName,
                                                 DnsRecordType qType,
                                                 String rewriteIp) {
        ProxyTunnelRequest request = ProxyWorkerTestFixtures.dnsRequest(
                inbound,
                user,
                0x4444,
                qName,
                qType,
                ProxyWorkerTestFixtures.socket("192.168.1.30", 5353));
        RouteConfig route = ProxyWorkerTestFixtures.route(RoutePolicy.DNS_REWRITING, ProtocolType.NONE, rewriteIp, null);
        request.setRouteConfig(route);
        return request;
    }

    private TestInbound newInbound() {
        UserConfig user = ProxyWorkerTestFixtures.user(9L, "dns-device", null, "192.168.1.30");
        InboundConfig inbound = ProxyWorkerTestFixtures.deviceInbound(ProtocolType.DNS_SERVER, user);
        CapturingHandler capture = new CapturingHandler();
        AddressedEmbeddedChannel channel = new AddressedEmbeddedChannel(
                ProxyWorkerTestFixtures.socket("192.168.1.1", 53),
                ProxyWorkerTestFixtures.socket("192.168.1.30", 5353),
                capture);
        return new TestInbound(user, inbound, channel, capture.ctx, ProxyWorkerTestFixtures.socket("192.168.1.30", 5353));
    }

    private record TestInbound(UserConfig user,
                               InboundConfig inbound,
                               AddressedEmbeddedChannel channel,
                               ChannelHandlerContext ctx,
                               java.net.InetSocketAddress client) {
    }

    private static final class CapturingHandler extends ChannelInboundHandlerAdapter {
        private ChannelHandlerContext ctx;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }
    }
}
