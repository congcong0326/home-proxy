package org.congcong.proxyworker.protocol.transparent;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.ProxyTimeContext;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.ProxyWorkerTestFixtures;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransparentServerHandlerTest {
    private UserConfig user;
    private InboundConfig inbound;
    private ProxyContext proxyContext;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        user = ProxyWorkerTestFixtures.user(8L, "living-room", null, "192.168.1.21");
        inbound = ProxyWorkerTestFixtures.deviceInbound(ProtocolType.TP_PROXY, user);
        proxyContext = ProxyWorkerTestFixtures.proxyContext("192.168.1.21", "93.184.216.34", 443);
        channel = new EmbeddedChannel(new TransparentServerHandler());
        ChannelAttributes.setInboundConfig(channel, inbound);
        ChannelAttributes.setProxyContext(channel, proxyContext);
        ChannelAttributes.setProxyTimeContext(channel, new ProxyTimeContext());
    }

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }

    @Test
    void createsTransparentTunnelRequestWithOriginalIpFallbackAndRetainedPayload() {
        channel.writeInbound(Unpooled.copiedBuffer("first packet", US_ASCII));

        ProxyTunnelRequest request = channel.readInbound();
        ByteBuf payload = request.getInitialPayload();
        try {
            assertEquals(ProtocolType.TP_PROXY, request.getProtocolType());
            assertEquals("93.184.216.34", request.getTargetHost());
            assertEquals("93.184.216.34", request.getTargetIp());
            assertEquals(443, request.getTargetPort());
            assertSame(user, request.getUser());
            assertSame(inbound, request.getInboundConfig());
            assertEquals(user.getUsername(), proxyContext.getUserName());
            assertEquals(user.getId(), proxyContext.getUserId());
            assertEquals("first packet", payload.toString(US_ASCII));
        } finally {
            payload.release();
            request.setInitialPayload(null);
        }
    }
}
