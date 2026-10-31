package org.congcong.controlmanager.service;

import org.congcong.common.enums.ProtocolType;
import org.congcong.controlmanager.entity.InboundConfig;
import org.congcong.controlmanager.entity.Outbound;
import org.congcong.controlmanager.entity.Route;
import org.congcong.controlmanager.enums.OutboundKind;
import org.congcong.controlmanager.enums.RouteAction;
import org.congcong.controlmanager.repository.OutboundRepository;
import org.congcong.controlmanager.repository.RewriteEntryRepository;
import org.congcong.controlmanager.repository.RouteRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FakeIpConfigValidatorTest {
    private final RouteRepository routes = mock(RouteRepository.class);
    private final OutboundRepository outbounds = mock(OutboundRepository.class);
    private final FakeIpConfigValidator validator = new FakeIpConfigValidator(routes,
            new RouteCompiler(outbounds, mock(RewriteEntryRepository.class)));

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
        fallback.setId(9L);
        fallback.setAction(RouteAction.FORWARD);
        fallback.setOutboundId(2L);
        Outbound upstream = new Outbound();
        upstream.setId(2L);
        upstream.setKind(OutboundKind.DNS_UPSTREAM);
        upstream.setProxyType(ProtocolType.DNS_SERVER);
        upstream.setHost("resolver.example");
        upstream.setPort(53);
        when(routes.findById(9L)).thenReturn(Optional.of(fallback));
        when(outbounds.findById(2L)).thenReturn(Optional.of(upstream));
        InboundConfig transparent = inbound(ProtocolType.TP_PROXY, false);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> validator.validate(List.of(dns, transparent))).getMessage().contains("IP 字面量"));
        upstream.setHost("1.1.1.1");
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
