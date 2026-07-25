package org.congcong.proxyworker.protocol.socks;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.netty.channel.embedded.EmbeddedChannel;
import org.congcong.proxyworker.ProxyWorkerTestFixtures;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.protocol.ProxyTunnelConnectorHandler;
import org.congcong.proxyworker.protocol.RequestAppendHandler;
import org.congcong.proxyworker.router.RouterService;
import org.congcong.proxyworker.server.ProxyContext;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.junit.jupiter.api.AfterEach;
import org.congcong.proxyworker.config.ProxyWorkerConfig;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

class SocksServerInitializerTest {

    @AfterEach
    void clearSelfSignedFlag() {
        System.clearProperty("TLS_ALLOW_SELF_SIGNED");
        resetWorkerConfig();
    }
    @AfterEach
    void tearDown() {
        resetActiveConnectionCount();
    }

    @Test
    void tlsEnabledSocksInboundInstallsSslBeforeProtocolAndRoutingHandlers() {
        System.setProperty("TLS_ALLOW_SELF_SIGNED", "true");
        resetWorkerConfig();
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

    private void resetWorkerConfig() {
        try {
            Field field = ProxyWorkerConfig.class.getDeclaredField("instance");
            field.setAccessible(true);
            field.set(null, null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void tcpInboundChannelContributesToActiveConnectionCountUntilClosed() {
        resetActiveConnectionCount();
        UserConfig user = ProxyWorkerTestFixtures.user(1L, "alice", "secret", null);
        InboundConfig inbound = ProxyWorkerTestFixtures.socksInbound(user);

        EmbeddedChannel channel = new EmbeddedChannel(new SocksServerInitializer(inbound));

        assertEquals(1, ProxyContext.getInstance().getActiveConnectionCount());

        channel.finishAndReleaseAll();

        assertEquals(0, ProxyContext.getInstance().getActiveConnectionCount());
    }

    private void resetActiveConnectionCount() {
        ProxyContext proxyContext = ProxyContext.getInstance();
        while (proxyContext.getActiveConnectionCount() > 0) {
            proxyContext.decrementActiveConnectionCount();
        }
    }
}
