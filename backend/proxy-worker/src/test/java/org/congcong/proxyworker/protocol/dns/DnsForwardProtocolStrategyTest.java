package org.congcong.proxyworker.protocol.dns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsSection;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.AddressedEmbeddedChannel;
import org.congcong.proxyworker.ProxyWorkerTestFixtures;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DnsForwardProtocolStrategyTest {
    private UserConfig user;
    private InboundConfig inboundConfig;
    private AddressedEmbeddedChannel inbound;
    private AddressedEmbeddedChannel outbound;
    private ChannelHandlerContext inboundCtx;
    private java.net.InetSocketAddress upstream;

    @BeforeEach
    void setUp() {
        user = ProxyWorkerTestFixtures.user(9L, "dns-device", null, "192.168.1.30");
        inboundConfig = ProxyWorkerTestFixtures.deviceInbound(ProtocolType.DNS_SERVER, user);
        CapturingHandler capture = new CapturingHandler();
        inbound = new AddressedEmbeddedChannel(
                ProxyWorkerTestFixtures.socket("192.168.1.1", 53),
                ProxyWorkerTestFixtures.socket("192.168.1.30", 5353),
                capture);
        inboundCtx = capture.ctx;
        upstream = ProxyWorkerTestFixtures.socket("1.1.1.1", 53);
        outbound = new AddressedEmbeddedChannel(
                ProxyWorkerTestFixtures.socket("192.168.1.1", 46000),
                upstream);
    }

    @AfterEach
    void tearDown() {
        inbound.finishAndReleaseAll();
        outbound.finishAndReleaseAll();
    }

    @Test
    void mapsConcurrentOutboundIdsBackToOriginalInboundIds() {
        DnsForwardProtocolStrategy strategy = new DnsForwardProtocolStrategy();

        strategy.onConnectSuccess(inboundCtx, outbound,
                dnsRequest(0x1111, "one.example.", DnsRecordType.A, "192.168.1.30", 5353));
        DatagramDnsQuery firstQuery = outbound.readOutbound();
        strategy.onConnectSuccess(inboundCtx, outbound,
                dnsRequest(0x2222, "two.example.", DnsRecordType.A, "192.168.1.31", 5354));
        DatagramDnsQuery secondQuery = outbound.readOutbound();

        try {
            assertNotEquals(firstQuery.id(), secondQuery.id());
            assertEquals("one.example.", firstQuestion(firstQuery).name());
            assertEquals("two.example.", firstQuestion(secondQuery).name());

            outbound.writeInbound(upstreamResponse(secondQuery.id(), "two.example.", DnsRecordType.A, 10, 0, 0, 2));
            DatagramDnsResponse secondClientResponse = inbound.readOutbound();
            try {
                assertEquals(0x2222, secondClientResponse.id());
                assertEquals("192.168.1.31", secondClientResponse.recipient().getAddress().getHostAddress());
                assertEquals(5354, secondClientResponse.recipient().getPort());
            } finally {
                secondClientResponse.release();
            }

            outbound.writeInbound(upstreamResponse(firstQuery.id(), "one.example.", DnsRecordType.A, 10, 0, 0, 1));
            DatagramDnsResponse firstClientResponse = inbound.readOutbound();
            try {
                assertEquals(0x1111, firstClientResponse.id());
                assertEquals("192.168.1.30", firstClientResponse.recipient().getAddress().getHostAddress());
                assertEquals(5353, firstClientResponse.recipient().getPort());
            } finally {
                firstClientResponse.release();
            }
        } finally {
            firstQuery.release();
            secondQuery.release();
        }
    }

    @Test
    void dropsUpstreamResponseWhenQuestionDoesNotMatchPendingRequest() {
        DnsForwardProtocolStrategy strategy = new DnsForwardProtocolStrategy();
        strategy.onConnectSuccess(inboundCtx, outbound,
                dnsRequest(0x3333, "expected.example.", DnsRecordType.A, "192.168.1.30", 5353));
        DatagramDnsQuery query = outbound.readOutbound();

        try {
            outbound.writeInbound(upstreamResponse(query.id(), "other.example.", DnsRecordType.A, 10, 0, 0, 9));

            assertNull(inbound.readOutbound());
        } finally {
            query.release();
        }
    }

    private ProxyTunnelRequest dnsRequest(int inboundId,
                                          String qName,
                                          DnsRecordType qType,
                                          String clientIp,
                                          int clientPort) {
        return ProxyWorkerTestFixtures.dnsRequest(
                inboundConfig,
                user,
                inboundId,
                qName,
                qType,
                ProxyWorkerTestFixtures.socket(clientIp, clientPort));
    }

    private DefaultDnsQuestion firstQuestion(DatagramDnsQuery query) {
        return query.recordAt(DnsSection.QUESTION);
    }

    private DatagramDnsResponse upstreamResponse(int id,
                                                 String qName,
                                                 DnsRecordType qType,
                                                 int... answerBytes) {
        DatagramDnsResponse response = new DatagramDnsResponse(upstream, ProxyWorkerTestFixtures.socket("192.168.1.1", 46000), id);
        response.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion(qName, qType));
        response.addRecord(DnsSection.ANSWER, new DefaultDnsRawRecord(
                qName,
                qType,
                60,
                Unpooled.wrappedBuffer(toBytes(answerBytes))));
        return response;
    }

    private byte[] toBytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    private static final class CapturingHandler extends ChannelInboundHandlerAdapter {
        private ChannelHandlerContext ctx;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }
    }
}
