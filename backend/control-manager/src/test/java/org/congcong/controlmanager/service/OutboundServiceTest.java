package org.congcong.controlmanager.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.congcong.common.enums.ProtocolType;
import org.congcong.controlmanager.dto.outbound.OutboundRequest;
import org.congcong.controlmanager.dto.outbound.OutboundView;
import org.congcong.controlmanager.entity.Outbound;
import org.congcong.controlmanager.entity.Route;
import org.congcong.controlmanager.enums.OutboundKind;
import org.congcong.controlmanager.repository.OutboundRepository;
import org.congcong.controlmanager.repository.RouteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class OutboundServiceTest {

    private final OutboundRepository outboundRepository = mock(OutboundRepository.class);
    private final RouteRepository routeRepository = mock(RouteRepository.class);
    private final OutboundService service = new OutboundService(outboundRepository, routeRepository);

    @Test
    void rejectsVlessRealityWhenRequiredConfigIsMissing() {
        OutboundRequest request = realityRequest(Map.of(
                "publicKey", "j4VYpQ2F2P7N5L3S0M9K8J6H4G2F1D0C",
                "shortId", "6ba85179e30d4fc2",
                "uuid", "11111111-1111-1111-1111-111111111111"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.createOutbound(request));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals("VLESS REALITY serverName is required", error.getReason());
    }

    @Test
    void keepsVlessRealityConfig() {
        when(outboundRepository.save(any(Outbound.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Map<String, Object> config = Map.of(
                "serverName", "www.example.com",
                "publicKey", "j4VYpQ2F2P7N5L3S0M9K8J6H4G2F1D0C",
                "shortId", "6ba85179e30d4fc2",
                "uuid", "11111111-1111-1111-1111-111111111111");

        OutboundView outbound = service.createOutbound(realityRequest(config));

        assertEquals(config, outbound.getConfig());
    }

    @Test
    void dnsUpstreamRequiresIpLiterals() {
        OutboundRequest request = new OutboundRequest();
        request.setName("dns");
        request.setKind(OutboundKind.DNS_UPSTREAM);
        request.setProxyType(ProtocolType.DNS_SERVER);
        request.setHost("8.8.8.8, dns.google");
        request.setPort(53);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.createOutbound(request));

        assertEquals("DNS 上游必须使用 IP 字面量: dns.google", error.getReason());
    }

    @Test
    void refusesDeletingReferencedOutbound() {
        Outbound outbound = new Outbound();
        outbound.setId(3L);
        outbound.setName("机场");
        outbound.setKind(OutboundKind.PROXY);
        Route route = new Route();
        route.setId(15L);
        route.setName("科学上网");
        when(outboundRepository.findById(3L)).thenReturn(Optional.of(outbound));
        when(routeRepository.findByOutboundId(3L)).thenReturn(List.of(route));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.deleteOutbound(3L));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("出站“机场”被以下路由引用，无法删除：“科学上网”（ID：15）", error.getReason());
        verify(outboundRepository, never()).delete(any());
    }

    @Test
    void refusesModifyingBuiltinOutbound() {
        Outbound direct = new Outbound();
        direct.setId(1L);
        direct.setBuiltinKey(OutboundService.BUILTIN_DIRECT);
        when(outboundRepository.findById(1L)).thenReturn(Optional.of(direct));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.updateOutbound(1L, realityRequest(Map.of())));

        assertEquals("内置出站不能修改", error.getReason());
    }

    private OutboundRequest realityRequest(Map<String, Object> config) {
        OutboundRequest request = new OutboundRequest();
        request.setName("reality");
        request.setKind(OutboundKind.PROXY);
        request.setProxyType(ProtocolType.VLESS_REALITY);
        request.setHost("reality.example.com");
        request.setPort(443);
        request.setConfig(config);
        return request;
    }
}
