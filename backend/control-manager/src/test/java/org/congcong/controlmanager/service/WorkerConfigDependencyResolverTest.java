package org.congcong.controlmanager.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

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

class WorkerConfigDependencyResolverTest {

    private final WorkerConfigDependencyResolver resolver = new WorkerConfigDependencyResolver(
            mock(InboundConfigRepository.class),
            mock(ProxyWorkerRepository.class),
            mock(UserRepository.class),
            mock(RouteRepository.class),
            mock(RuleSetRepository.class));

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
}
