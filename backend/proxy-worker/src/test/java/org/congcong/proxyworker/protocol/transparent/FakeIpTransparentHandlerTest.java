package org.congcong.proxyworker.protocol.transparent;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.ProxyTimeContext;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.dns.FakeIpRegistry;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.LogicalTargetSource;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FakeIpTransparentHandlerTest {
    @Test
    void mappedAddressStartsRoutingBeforeFirstPayload() throws Exception {
        String fakeIp = FakeIpRegistry.getInstance().getOrAllocate("mapped-first-packet-test.example");
        TestChannel channel = channel(fakeIp);
        channel.pipeline().fireChannelActive();
        ProxyTunnelRequest request = channel.readInbound();
        assertNotNull(request);
        assertEquals("mapped-first-packet-test.example", request.getTargetHost());
        assertEquals(fakeIp, request.getOriginalTargetIp());
        assertNull(request.getTargetIp());
        assertNull(request.getInitialPayload());
        assertEquals(LogicalTargetSource.FAKE_IP, request.getLogicalTargetSource());
        assertNull(channel.pipeline().get(ProtocolDetectHandler.class));
        channel.finishAndReleaseAll();
    }

    @Test
    void unmappedPoolAddressNeverRoutesAsLiteral() throws Exception {
        TestChannel channel = channel("198.19.255.254");
        channel.pipeline().fireChannelActive();
        channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4, 5}));
        assertNull(channel.readInbound());
        assertFalse(channel.isActive());
        channel.finishAndReleaseAll();
    }

    private TestChannel channel(String destinationIp) {
        TestChannel channel = new TestChannel();
        InboundConfig inbound = new InboundConfig();
        inbound.setProtocol(ProtocolType.TP_PROXY);
        UserConfig anonymous = new UserConfig();
        anonymous.setId(7L);
        anonymous.setUsername("anonymous");
        inbound.setAnonymousUser(anonymous);
        inbound.setDeviceIpMapUser(Map.of());
        ProxyContext context = new ProxyContext();
        context.setClientIp("192.0.2.9");
        context.setOriginalTargetIP(destinationIp);
        context.setOriginalTargetPort(443);
        ChannelAttributes.setInboundConfig(channel, inbound);
        ChannelAttributes.setProxyContext(channel, context);
        ChannelAttributes.setProxyTimeContext(channel, new ProxyTimeContext());
        return channel;
    }

    private static final class TestChannel extends EmbeddedChannel {
        private TestChannel() {
            super();
            pipeline().addLast(new FakeIpTransparentHandler(), new ProtocolDetectHandler(),
                    new TransparentServerHandler());
        }

        @Override protected SocketAddress localAddress0() {
            return new InetSocketAddress("198.18.0.1", 443);
        }

        @Override protected SocketAddress remoteAddress0() {
            return new InetSocketAddress("192.0.2.9", 50000);
        }
    }
}
