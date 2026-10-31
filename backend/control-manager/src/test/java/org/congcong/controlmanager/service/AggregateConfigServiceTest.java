package org.congcong.controlmanager.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.congcong.common.dto.AggregateConfigResponse;
import org.congcong.common.dto.InboundConfigDTO;
import org.congcong.common.dto.InboundRouteBinding;
import org.congcong.common.dto.RouteDTO;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.dto.RuleSetDTO;
import org.congcong.common.dto.UserDtoWithCredential;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RouteConditionType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.controlmanager.entity.InboundConfig;
import org.congcong.controlmanager.entity.Outbound;
import org.congcong.controlmanager.entity.RewriteEntry;
import org.congcong.controlmanager.entity.Route;
import org.congcong.controlmanager.enums.OutboundKind;
import org.congcong.controlmanager.enums.RouteAction;
import org.congcong.controlmanager.repository.OutboundRepository;
import org.congcong.controlmanager.repository.RewriteEntryRepository;
import org.congcong.controlmanager.repository.InboundConfigRepository;
import org.congcong.controlmanager.repository.ProxyWorkerRepository;
import org.congcong.controlmanager.repository.RouteRepository;
import org.congcong.controlmanager.repository.RuleSetRepository;
import org.congcong.controlmanager.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;

class AggregateConfigServiceTest {
    private static final String WORKER_ID = "worker-1";

    private final InboundConfigService inboundService = mock(InboundConfigService.class);
    private final OutboundRepository outboundRepository = mock(OutboundRepository.class);
    private final RewriteEntryRepository rewriteEntryRepository = mock(RewriteEntryRepository.class);
    private final RouteCompiler routeCompiler = new RouteCompiler(outboundRepository, rewriteEntryRepository);
    private final UserService userService = mock(UserService.class);
    private final RuleSetService ruleSetService = mock(RuleSetService.class);
    private final InboundConfigRepository inboundRepository = mock(InboundConfigRepository.class);
    private final RouteRepository routeRepository = mock(RouteRepository.class);
    private final ProxyWorkerRepository workerRepository = mock(ProxyWorkerRepository.class);
    private final WorkerConfigDependencyResolver resolver = new WorkerConfigDependencyResolver(
            inboundRepository, workerRepository, mock(UserRepository.class), routeRepository,
            mock(RuleSetRepository.class));
    private final AggregateConfigService service = new AggregateConfigService(
            inboundService, routeCompiler, userService, ruleSetService,
            inboundRepository, routeRepository, resolver, new CanonicalConfigHasher(),
            new FakeIpConfigValidator(routeRepository, routeCompiler), workerRepository);
    private final List<Route> routes = new ArrayList<>();
    private final List<Outbound> outbounds = new ArrayList<>(List.of(outbound(1L, OutboundKind.DIRECT)));
    private final List<RewriteEntry> entries = new ArrayList<>();
    private final InboundConfig inbound = new InboundConfig();
    private final InboundRouteBinding binding = new InboundRouteBinding();

    @BeforeEach
    void setUp() {
        inbound.setId(1L);
        inbound.setWorkerId(WORKER_ID);
        inbound.setProtocol(ProtocolType.HTTPS_CONNECT);
        inbound.setAnonymousUserId(11L);
        binding.setUserIds(List.of(11L, 12L));
        binding.setRouteIds(List.of());
        inbound.setInboundRouteBindings(List.of(binding));
        when(inboundRepository.findByWorkerIdAndStatusOrderByIdAsc(WORKER_ID, 1)).thenReturn(List.of(inbound));
        when(inboundService.getEnabledInboundConfigsForWorker(WORKER_ID)).thenAnswer(invocation -> {
            InboundConfigDTO dto = new InboundConfigDTO();
            BeanUtils.copyProperties(inbound, dto);
            return List.of(dto);
        });
        when(routeRepository.findAllById(any())).thenAnswer(invocation -> {
            Iterable<Long> ids = invocation.getArgument(0);
            return StreamSupport.stream(ids.spliterator(), false)
                    .flatMap(id -> routes.stream().filter(route -> route.getId().equals(id))).toList();
        });
        when(routeRepository.findById(any())).thenAnswer(invocation ->
                routes.stream().filter(route -> route.getId().equals(invocation.getArgument(0))).findFirst());
        when(outboundRepository.findAllById(any())).thenAnswer(invocation -> {
            Iterable<Long> ids = invocation.getArgument(0);
            return StreamSupport.stream(ids.spliterator(), false)
                    .flatMap(id -> outbounds.stream().filter(outbound -> outbound.getId().equals(id))).toList();
        });
        when(outboundRepository.findById(any())).thenAnswer(invocation ->
                outbounds.stream().filter(outbound -> outbound.getId().equals(invocation.getArgument(0))).findFirst());
        when(rewriteEntryRepository.findByTableIdInOrderBySortOrderAscIdAsc(any())).thenAnswer(invocation -> {
            java.util.Collection<Long> tableIds = invocation.getArgument(0);
            return entries.stream().filter(entry -> tableIds.contains(entry.getTableId())).toList();
        });
        when(userService.findUserDTOWithCredentialByStatus(1)).thenReturn(List.of(user(11L), user(12L)));
        when(workerRepository.findById(WORKER_ID)).thenReturn(Optional.empty());
    }

    @Test
    void filtersDisabledRoutesFromBindingsAndPreservesOrderWithoutMutatingStoredBindings() {
        route(21L, 1);
        route(22L, 0);
        route(23L, 1);
        route(99L, 1); // Unrelated routes must not be published to this worker.
        binding.setRouteIds(Arrays.asList(23L, null, 22L, 21L));
        inbound.setFallbackRouteId(21L);

        AggregateConfigResponse config = service.getAggregateConfig(WORKER_ID);
        InboundConfigDTO runtime = config.getInbounds().get(0);

        assertEquals(List.of(21L, 23L), config.getRoutes().stream().map(RouteDTO::getId).toList());
        assertEquals(List.of(23L, 21L), runtime.getInboundRouteBindings().get(0).getRouteIds());
        assertEquals(21L, runtime.getFallbackRouteId());
        assertEquals(Arrays.asList(23L, null, 22L, 21L), binding.getRouteIds());
        assertNotSame(binding, runtime.getInboundRouteBindings().get(0));
        runtime.getInboundRouteBindings().get(0).getUserIds().clear();
        assertEquals(List.of(11L, 12L), binding.getUserIds());
    }

    @Test
    void keepsUserAuthenticationWhenAllRoutesAndFallbackAreDisabled() {
        route(21L, 0);
        binding.setRouteIds(List.of(21L));
        inbound.setFallbackRouteId(21L);

        AggregateConfigResponse config = service.getAggregateConfig(WORKER_ID);
        InboundConfigDTO runtime = config.getInbounds().get(0);

        assertTrue(config.getRoutes().isEmpty());
        assertNull(runtime.getFallbackRouteId());
        assertTrue(runtime.getInboundRouteBindings().get(0).getRouteIds().isEmpty());
        assertEquals(List.of(11L, 12L), runtime.getInboundRouteBindings().get(0).getUserIds());
        assertEquals(11L, runtime.getAnonymousUserId());
        assertEquals(List.of(11L, 12L), config.getUsers().stream().map(UserDtoWithCredential::getId).toList());
        assertEquals(21L, inbound.getFallbackRouteId());
    }

    @Test
    void restoresBindingsAndFallbackWhenRouteIsReenabledAndChangesConfigHash() {
        Route route = route(21L, 1);
        binding.setRouteIds(List.of(21L));
        inbound.setFallbackRouteId(21L);
        AggregateConfigResponse enabled = service.getAggregateConfig(WORKER_ID);

        route.setStatus(0);
        AggregateConfigResponse disabled = service.getAggregateConfig(WORKER_ID);
        assertNotEquals(enabled.getConfigHash(), disabled.getConfigHash());

        route.setStatus(1);
        AggregateConfigResponse restored = service.getAggregateConfig(WORKER_ID);
        assertEquals(enabled.getConfigHash(), restored.getConfigHash());
        assertEquals(List.of(21L), restored.getInbounds().get(0).getInboundRouteBindings().get(0).getRouteIds());
        assertEquals(21L, restored.getInbounds().get(0).getFallbackRouteId());
    }

    @Test
    void excludesRuleSetsUsedOnlyByDisabledRoutes() {
        route(21L, 0).setRules(List.of(ruleSetRule("disabled-only")));
        route(22L, 1).setRules(List.of(ruleSetRule("enabled")));
        binding.setRouteIds(List.of(21L, 22L));
        RuleSetDTO disabledRuleSet = new RuleSetDTO();
        disabledRuleSet.setRuleKey("disabled-only");
        RuleSetDTO enabledRuleSet = new RuleSetDTO();
        enabledRuleSet.setRuleKey("enabled");
        when(ruleSetService.getPublishedRuleSetsWithItems()).thenReturn(List.of(disabledRuleSet, enabledRuleSet));

        assertEquals(List.of(enabledRuleSet), service.getAggregateConfig(WORKER_ID).getRuleSets());
        // Disabling or unpublishing the unused rule set must not block delivery either.
        when(ruleSetService.getPublishedRuleSetsWithItems()).thenReturn(List.of(enabledRuleSet));
        assertEquals(List.of(enabledRuleSet), service.getAggregateConfig(WORKER_ID).getRuleSets());
    }

    @Test
    void disablesRuntimeFakeIpWhenItsFallbackIsDisabledAndRestoresItOnReenable() {
        Route route = route(21L, 1);
        Outbound upstream = outbound(2L, OutboundKind.DNS_UPSTREAM);
        upstream.setProxyType(ProtocolType.DNS_SERVER);
        upstream.setHost("8.8.8.8");
        upstream.setPort(53);
        outbounds.add(upstream);
        route.setOutboundId(2L);
        inbound.setProtocol(ProtocolType.DNS_SERVER);
        inbound.setPort(5353);
        inbound.setListenIp("127.0.0.1");
        inbound.setFakeIpEnabled(true);
        inbound.setFallbackRouteId(21L);
        InboundConfig transparent = new InboundConfig();
        transparent.setProtocol(ProtocolType.TP_PROXY);
        when(inboundService.getEnabledInboundConfigsForWorker(WORKER_ID)).thenAnswer(invocation -> {
            InboundConfigDTO dnsDto = new InboundConfigDTO();
            BeanUtils.copyProperties(inbound, dnsDto);
            InboundConfigDTO transparentDto = new InboundConfigDTO();
            BeanUtils.copyProperties(transparent, transparentDto);
            return List.of(dnsDto, transparentDto);
        });

        AggregateConfigResponse enabled = service.getAggregateConfig(WORKER_ID);
        route.setStatus(0);
        InboundConfigDTO disabled = service.getAggregateConfig(WORKER_ID).getInbounds().get(0);
        assertNull(disabled.getFallbackRouteId());
        assertFalse(disabled.getFakeIpEnabled());
        assertTrue(inbound.getFakeIpEnabled());
        assertEquals(21L, inbound.getFallbackRouteId());

        route.setStatus(1);
        AggregateConfigResponse restored = service.getAggregateConfig(WORKER_ID);
        assertTrue(restored.getInbounds().get(0).getFakeIpEnabled());
        assertEquals(enabled.getConfigHash(), restored.getConfigHash());
    }

    @Test
    void expandsRewriteTableRouteInPlaceWithStableSyntheticIds() {
        route(21L, 1);
        tableRoute(22L, 5L);
        route(23L, 1);
        entry(7L, 5L, "my-emby.com", true);
        entry(8L, 5L, "disabled.com", false);
        entry(9L, 5L, "*.my-emby.com", true);
        binding.setRouteIds(List.of(21L, 22L, 23L));

        AggregateConfigResponse config = service.getAggregateConfig(WORKER_ID);

        long first = RouteCompiler.REWRITE_ROUTE_ID_BASE + 7;
        long second = RouteCompiler.REWRITE_ROUTE_ID_BASE + 9;
        assertEquals(List.of(21L, first, second, 23L),
                config.getInbounds().get(0).getInboundRouteBindings().get(0).getRouteIds());
        RouteDTO expanded = config.getRoutes().stream().filter(route -> route.getId() == first).findFirst().orElseThrow();
        assertEquals("NAS / my-emby.com", expanded.getName());
        assertEquals(RoutePolicy.DESTINATION_OVERRIDE, expanded.getPolicy());
        assertEquals("192.168.10.85", expanded.getOutboundProxyHost());
        assertEquals(8007, expanded.getOutboundProxyPort());
        assertEquals("my-emby.com", expanded.getRules().get(0).getValue());
        assertEquals(List.of(21L, 23L, first, second), config.getRoutes().stream().map(RouteDTO::getId).toList());
    }

    @Test
    void emptyRewriteTableExpandsToNothing() {
        tableRoute(22L, 5L);
        binding.setRouteIds(List.of(22L));

        AggregateConfigResponse config = service.getAggregateConfig(WORKER_ID);

        assertTrue(config.getRoutes().isEmpty());
        assertTrue(config.getInbounds().get(0).getInboundRouteBindings().get(0).getRouteIds().isEmpty());
    }

    @Test
    void compilesForwardRouteWithProxyOutboundFields() {
        Route route = route(21L, 1);
        Outbound proxy = outbound(3L, OutboundKind.PROXY);
        proxy.setProxyType(ProtocolType.SHADOW_SOCKS);
        proxy.setHost("ss.example.com");
        proxy.setPort(8388);
        proxy.setPassword("secret");
        outbounds.add(proxy);
        route.setOutboundId(3L);
        route.setOutboundTag("legacy-tag");
        binding.setRouteIds(List.of(21L));

        RouteDTO compiled = service.getAggregateConfig(WORKER_ID).getRoutes().get(0);

        assertEquals(RoutePolicy.OUTBOUND_PROXY, compiled.getPolicy());
        assertEquals(ProtocolType.SHADOW_SOCKS, compiled.getOutboundProxyType());
        assertEquals("ss.example.com", compiled.getOutboundProxyHost());
        assertEquals(8388, compiled.getOutboundProxyPort());
        assertEquals("secret", compiled.getOutboundProxyPassword());
        assertEquals("legacy-tag", compiled.getOutboundTag());
    }

    @Test
    void handlesInboundsWithoutBindingsOrFallback() {
        inbound.setInboundRouteBindings(null);
        when(userService.findUserDTOWithCredentialByStatus(1)).thenReturn(List.of(user(11L)));

        AggregateConfigResponse config = service.getAggregateConfig(WORKER_ID);

        assertTrue(config.getRoutes().isEmpty());
        assertNull(config.getInbounds().get(0).getInboundRouteBindings());
        assertNull(config.getInbounds().get(0).getFallbackRouteId());
    }

    @Test
    void stillRejectsMissingRoutes() {
        binding.setRouteIds(List.of(404L));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.getAggregateConfig(WORKER_ID));

        assertEquals("入站引用了不存在的路由", error.getMessage());
    }

    private Route route(long id, int status) {
        Route route = new Route();
        route.setId(id);
        route.setStatus(status);
        route.setAction(RouteAction.FORWARD);
        route.setOutboundId(1L);
        route.setPolicy(RoutePolicy.DIRECT);
        routes.add(route);
        return route;
    }

    private Route tableRoute(long id, long tableId) {
        Route route = route(id, 1);
        route.setName("NAS");
        route.setRules(List.of());
        route.setAction(RouteAction.REWRITE_DESTINATION);
        route.setRewriteTableId(tableId);
        return route;
    }

    private RewriteEntry entry(long id, long tableId, String pattern, boolean enabled) {
        RewriteEntry entry = new RewriteEntry();
        entry.setId(id);
        entry.setTableId(tableId);
        entry.setDomainPattern(pattern);
        entry.setTargetHost("192.168.10.85");
        entry.setTargetPort(8000 + (int) id);
        entry.setEnabled(enabled);
        entries.add(entry);
        return entry;
    }

    private static Outbound outbound(long id, OutboundKind kind) {
        Outbound outbound = new Outbound();
        outbound.setId(id);
        outbound.setKind(kind);
        return outbound;
    }

    private UserDtoWithCredential user(long id) {
        UserDtoWithCredential user = new UserDtoWithCredential();
        user.setId(id);
        return user;
    }

    private RouteRule ruleSetRule(String key) {
        RouteRule rule = new RouteRule();
        rule.setConditionType(RouteConditionType.RULE_SET);
        rule.setValue(key);
        return rule;
    }
}
