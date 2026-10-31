package org.congcong.controlmanager.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.congcong.common.dto.InboundRouteBinding;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.RouteConditionType;
import org.congcong.controlmanager.entity.InboundConfig;
import org.congcong.controlmanager.entity.Route;
import org.congcong.controlmanager.repository.InboundConfigRepository;
import org.congcong.controlmanager.repository.ProxyWorkerRepository;
import org.congcong.controlmanager.repository.RouteRepository;
import org.congcong.controlmanager.repository.RuleSetRepository;
import org.congcong.controlmanager.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class WorkerConfigDependencyResolverTest {

    private final InboundConfigRepository inboundConfigRepository = mock(InboundConfigRepository.class);
    private final ProxyWorkerRepository proxyWorkerRepository = mock(ProxyWorkerRepository.class);
    private final RouteRepository routeRepository = mock(RouteRepository.class);
    private final RuleSetRepository ruleSetRepository = mock(RuleSetRepository.class);
    private final WorkerConfigDependencyResolver resolver = new WorkerConfigDependencyResolver(
            inboundConfigRepository,
            proxyWorkerRepository,
            mock(UserRepository.class),
            routeRepository,
            ruleSetRepository);

    @Test
    void collectsExplicitAndJsonInboundReferences() {
        InboundConfig inbound = new InboundConfig();
        inbound.setAnonymousUserId(11L);
        inbound.setFallbackRouteId(21L);
        InboundRouteBinding binding = new InboundRouteBinding();
        binding.setUserIds(List.of(12L, 13L));
        binding.setRouteIds(List.of(22L, 23L));
        inbound.setInboundRouteBindings(Arrays.asList(null, binding));

        assertEquals(Set.of(11L, 12L, 13L), resolver.resolveUserIds(List.of(inbound)));
        assertEquals(Set.of(21L, 22L, 23L), resolver.resolveRouteIds(List.of(inbound)));
    }

    @Test
    void ignoresNullRoutesAndRulesWhenResolvingRuleSets() {
        Route route = new Route();
        RouteRule rule = new RouteRule();
        rule.setConditionType(RouteConditionType.RULE_SET);
        rule.setValue("ai-common");
        route.setRules(Arrays.asList(null, rule));

        assertEquals(Set.of("ai-common"), resolver.resolveRuleSetKeys(Arrays.asList(null, route)));
    }

    @Test
    void findsEveryReferencingInboundIncludingDisabledAndDuplicateBindings() {
        InboundConfig fallback = new InboundConfig();
        fallback.setFallbackRouteId(21L);
        InboundConfig disabled = new InboundConfig();
        disabled.setStatus(0);
        InboundRouteBinding binding = new InboundRouteBinding();
        binding.setRouteIds(Arrays.asList(null, 21L, 22L));
        disabled.setInboundRouteBindings(Arrays.asList(null, binding, binding));
        InboundConfig unrelated = new InboundConfig();
        unrelated.setFallbackRouteId(22L);
        when(inboundConfigRepository.findAll()).thenReturn(List.of(fallback, disabled, unrelated));

        assertEquals(List.of(fallback, disabled), resolver.findInboundsReferencingRoute(21L));
        assertTrue(resolver.isRouteReferenced(21L));
        assertEquals(List.of(), resolver.findInboundsReferencingRoute(99L));
        assertFalse(resolver.isRouteReferenced(99L));
    }

    @Test
    void allowsDisabledRouteReferencesAndSkipsTheirRuleSets() {
        Route enabled = new Route();
        enabled.setId(21L);
        enabled.setStatus(1);
        Route disabled = new Route();
        disabled.setId(22L);
        disabled.setStatus(0);
        RouteRule rule = new RouteRule();
        rule.setConditionType(RouteConditionType.RULE_SET);
        rule.setValue("unpublished");
        disabled.setRules(List.of(rule));
        InboundConfig inbound = inboundReferencing(21L, 22L);
        when(proxyWorkerRepository.existsById("home-01")).thenReturn(true);
        when(routeRepository.findAllById(Set.of(21L, 22L))).thenReturn(List.of(enabled, disabled));

        assertDoesNotThrow(() -> resolver.validateInboundReferences(inbound));
    }

    @Test
    void rejectsDeletedRouteReferences() {
        Route enabled = new Route();
        enabled.setId(21L);
        enabled.setStatus(1);
        InboundConfig inbound = inboundReferencing(21L, 99L);
        when(proxyWorkerRepository.existsById("home-01")).thenReturn(true);
        when(routeRepository.findAllById(Set.of(21L, 99L))).thenReturn(List.of(enabled));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> resolver.validateInboundReferences(inbound));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals("入站引用的路由不存在", error.getReason());
    }

    private InboundConfig inboundReferencing(Long... routeIds) {
        InboundConfig inbound = new InboundConfig();
        inbound.setWorkerId("home-01");
        InboundRouteBinding binding = new InboundRouteBinding();
        binding.setRouteIds(List.of(routeIds));
        inbound.setInboundRouteBindings(List.of(binding));
        return inbound;
    }
}
