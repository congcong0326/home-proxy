package org.congcong.proxyworker.outbound.direct;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.SocketChannel;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.security.ProxySafetyCounters;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DirectOutboundConnectorTest {
    @Test
    void rejectsPoolLiteralFromTransparentHostBeforeCreatingConnection() {
        assertPoolRejected(ProtocolType.TP_PROXY, RoutePolicy.DIRECT, "198.18.0.20");
        assertPoolRejected(ProtocolType.TP_PROXY, RoutePolicy.DIRECT, "198.19.255.255");
    }

    @Test
    void rejectsConfiguredPoolOverrideEvenForProtectedInbounds() {
        assertPoolRejected(ProtocolType.SOCKS5, RoutePolicy.DESTINATION_OVERRIDE, "198.18.0.0");
        assertPoolRejected(ProtocolType.HTTPS_CONNECT, RoutePolicy.DESTINATION_OVERRIDE, "[::ffff:198.18.0.20]");
    }

    private void assertPoolRejected(ProtocolType protocol, RoutePolicy policy, String host) {
        EmbeddedChannel inbound = new EmbeddedChannel();
        try {
            RouteConfig route = new RouteConfig();
            route.setPolicy(policy);
            route.setOutboundProxyHost(host);
            route.setOutboundProxyPort(80);
            ProxyTunnelRequest request = new ProxyTunnelRequest(protocol,
                    policy == RoutePolicy.DIRECT ? host : "example.com", 80, null, new InboundConfig(), null);
            request.setRouteConfig(route);
            long rejected = ProxySafetyCounters.rejectedPoolAddresses();
            DirectOutboundConnector connector = new DirectOutboundConnector() {
                @Override protected Class<? extends SocketChannel> getSocketChannel() {
                    fail("A pool destination must be rejected before a socket is created");
                    return super.getSocketChannel();
                }
            };
            var connection = connector.connect(inbound, request, inbound.eventLoop().newPromise());
            assertTrue(connection.isDone());
            assertFalse(connection.isSuccess());
            assertInstanceOf(IllegalArgumentException.class, connection.cause());
            assertEquals(rejected + 1, ProxySafetyCounters.rejectedPoolAddresses());
        } finally {
            inbound.finishAndReleaseAll();
        }
    }
}
