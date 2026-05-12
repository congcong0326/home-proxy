package org.congcong.proxyworker.outbound;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.Map;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.outbound.reality.VlessRealityOutboundConnector;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.junit.jupiter.api.Test;

class OutboundConnectorFactoryTest {

    @Test
    void createsVlessRealityConnectorForVlessRealityOutboundProxyRoute() {
        RouteConfig route = new RouteConfig();
        route.setPolicy(RoutePolicy.OUTBOUND_PROXY);
        route.setOutboundProxyType(ProtocolType.VLESS_REALITY);
        route.setOutboundProxyHost("reality.example.com");
        route.setOutboundProxyPort(443);
        route.setOutboundProxyConfig(Map.of(
                "serverName", "www.example.com",
                "publicKey", "j4VYpQ2F2P7N5L3S0M9K8J6H4G2F1D0C",
                "shortId", "6ba85179e30d4fc2",
                "uuid", "11111111-1111-1111-1111-111111111111"));

        ProxyTunnelRequest request = new ProxyTunnelRequest(
                ProtocolType.HTTPS_CONNECT,
                "target.example.com",
                443,
                null,
                null,
                null);
        request.setRouteConfig(route);

        assertInstanceOf(VlessRealityOutboundConnector.class, OutboundConnectorFactory.create(request));
    }
}
