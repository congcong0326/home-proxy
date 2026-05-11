package org.congcong.proxyworker.protocol.socks;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.netty.channel.embedded.EmbeddedChannel;
import org.congcong.proxyworker.ProxyWorkerTestFixtures;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.protocol.ProxyTunnelConnectorHandler;
import org.congcong.proxyworker.protocol.RequestAppendHandler;
import org.congcong.proxyworker.router.RouterService;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.junit.jupiter.api.Test;

class SocksServerInitializerTest {
    @Test
    void tlsEnabledSocksInboundInstallsSslBeforeProtocolAndRoutingHandlers() {
        UserConfig user = ProxyWorkerTestFixtures.user(1L, "alice", "secret", null);
        InboundConfig inbound = ProxyWorkerTestFixtures.socksInbound(user);
        inbound.setTlsEnabled(true);

        EmbeddedChannel channel = new EmbeddedChannel(new SocksServerInitializer(inbound));

        assertNotNull(channel.pipeline().get("ssl"));
        assertNotNull(channel.pipeline().get(SocksServerHandler.class));
        assertNotNull(channel.pipeline().get(RouterService.class));
        assertNotNull(channel.pipeline().get(RequestAppendHandler.class));
        assertNotNull(channel.pipeline().get(ProxyTunnelConnectorHandler.class));
        assertSame(inbound, ChannelAttributes.getInboundConfig(channel));

        channel.finishAndReleaseAll();
    }
}
