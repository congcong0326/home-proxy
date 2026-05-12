package org.congcong.proxyworker.outbound.reality.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.UUID;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.outbound.reality.vless.VlessFlow;
import org.junit.jupiter.api.Test;

class VlessRealityOutboundConfigTest {

    @Test
    void buildsConfigFromRouteOutboundProxyConfig() {
        RouteConfig route = baseRoute(Map.of(
                "serverName", "www.example.com",
                "publicKey", "j4VYpQ2F2P7N5L3S0M9K8J6H4G2F1D0C",
                "shortId", "6ba85179e30d4fc2",
                "uuid", "11111111-1111-1111-1111-111111111111"));

        VlessRealityOutboundConfig config = VlessRealityOutboundConfig.from(route);

        assertEquals("reality.example.com", config.host());
        assertEquals(443, config.port());
        assertEquals("www.example.com", config.serverName());
        assertEquals("j4VYpQ2F2P7N5L3S0M9K8J6H4G2F1D0C", config.publicKey());
        assertEquals("6ba85179e30d4fc2", config.shortId());
        assertEquals(UUID.fromString("11111111-1111-1111-1111-111111111111"), config.uuid());
        assertEquals(VlessFlow.XTLS_RPRX_VISION, config.flow());
        assertEquals(10000, config.connectTimeoutMillis());
    }

    @Test
    void rejectsMissingRequiredRealityFields() {
        RouteConfig route = baseRoute(Map.of(
                "publicKey", "j4VYpQ2F2P7N5L3S0M9K8J6H4G2F1D0C",
                "shortId", "6ba85179e30d4fc2",
                "uuid", "11111111-1111-1111-1111-111111111111"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> VlessRealityOutboundConfig.from(route));

        assertEquals("VLESS REALITY serverName is required", error.getMessage());
    }

    @Test
    void rejectsNonHexShortId() {
        RouteConfig route = baseRoute(Map.of(
                "serverName", "www.example.com",
                "publicKey", "j4VYpQ2F2P7N5L3S0M9K8J6H4G2F1D0C",
                "shortId", "not-hex",
                "uuid", "11111111-1111-1111-1111-111111111111"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> VlessRealityOutboundConfig.from(route));

        assertEquals("Invalid REALITY shortId hex: not-hex", error.getMessage());
    }

    private RouteConfig baseRoute(Map<String, Object> outboundProxyConfig) {
        RouteConfig route = new RouteConfig();
        route.setPolicy(RoutePolicy.OUTBOUND_PROXY);
        route.setOutboundProxyType(ProtocolType.VLESS_REALITY);
        route.setOutboundProxyHost("reality.example.com");
        route.setOutboundProxyPort(443);
        route.setOutboundProxyConfig(outboundProxyConfig);
        return route;
    }
}
