package org.congcong.controlmanager.service;

import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.controlmanager.entity.InboundConfig;
import org.congcong.controlmanager.entity.Route;
import org.congcong.controlmanager.repository.RouteRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FakeIpConfigValidatorTest {
    private final RouteRepository routes = mock(RouteRepository.class);
    private final FakeIpConfigValidator validator = new FakeIpConfigValidator(routes);

    @Test
    void rejectsDuplicateEnabledInboundsEvenWhenFakeIpIsOff() {
        InboundConfig first = inbound(ProtocolType.DNS_SERVER, false);
        InboundConfig second = inbound(ProtocolType.DNS_SERVER, false);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> validator.validate(List.of(first, second))).getMessage().contains("多个启用的 DNS"));
        second.setStatus(0);
        assertDoesNotThrow(() -> validator.validate(List.of(first, second)));
    }

    @Test
    void requiresTransparentInboundAndLiteralRealUpstream() {
        InboundConfig dns = inbound(ProtocolType.DNS_SERVER, true);
        dns.setFallbackRouteId(9L);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> validator.validate(List.of(dns))).getMessage().contains("透明代理"));

        Route fallback = new Route();
        fallback.setPolicy(RoutePolicy.OUTBOUND_PROXY);
        fallback.setOutboundProxyType(ProtocolType.DNS_SERVER);
        fallback.setOutboundProxyHost("resolver.example");
        fallback.setOutboundProxyPort(53);
        when(routes.findById(9L)).thenReturn(Optional.of(fallback));
        InboundConfig transparent = inbound(ProtocolType.TP_PROXY, false);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> validator.validate(List.of(dns, transparent))).getMessage().contains("IP 字面量"));
        fallback.setOutboundProxyHost("1.1.1.1");
        assertDoesNotThrow(() -> validator.validate(List.of(dns, transparent)));
    }

    private InboundConfig inbound(ProtocolType protocol, boolean fakeIp) {
        InboundConfig inbound = new InboundConfig();
        inbound.setProtocol(protocol);
        inbound.setFakeIpEnabled(fakeIp);
        inbound.setStatus(1);
        inbound.setPort(protocol == ProtocolType.DNS_SERVER ? 53 : 12345);
        inbound.setListenIp("0.0.0.0");
        return inbound;
    }
}
