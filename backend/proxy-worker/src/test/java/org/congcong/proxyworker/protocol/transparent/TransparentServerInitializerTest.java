package org.congcong.proxyworker.protocol.transparent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.congcong.common.dto.ProxyContext;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.AddressedEmbeddedChannel;
import org.congcong.proxyworker.ProxyWorkerTestFixtures;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.junit.jupiter.api.Test;

class TransparentServerInitializerTest {
    @Test
    void fillsOriginalDestinationFromLocalAddressWithoutTlsHandler() {
        UserConfig user = ProxyWorkerTestFixtures.user(8L, "living-room", null, "192.168.1.21");
        InboundConfig inbound = ProxyWorkerTestFixtures.deviceInbound(ProtocolType.TP_PROXY, user);

        AddressedEmbeddedChannel channel = new AddressedEmbeddedChannel(
                ProxyWorkerTestFixtures.socket("203.0.113.10", 8443),
                ProxyWorkerTestFixtures.socket("192.168.1.21", 53000),
                new TransparentServerInitializer(inbound));

        ProxyContext proxyContext = ChannelAttributes.getProxyContext(channel);
        assertEquals("203.0.113.10", proxyContext.getOriginalTargetIP());
        assertEquals(8443, proxyContext.getOriginalTargetPort());
        assertNull(channel.pipeline().get("ssl"));
        assertNotNull(channel.pipeline().get(ProtocolDetectHandler.class));
        assertNotNull(channel.pipeline().get(TransparentServerHandler.class));

        channel.finishAndReleaseAll();
    }
}
