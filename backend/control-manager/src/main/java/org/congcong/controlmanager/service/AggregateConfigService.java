package org.congcong.controlmanager.service;

import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.AggregateConfigResponse;
import org.congcong.common.dto.InboundConfigDTO;
import org.congcong.common.dto.InboundRouteBinding;
import org.congcong.common.dto.RouteDTO;
import org.congcong.common.dto.RuleSetDTO;
import org.congcong.common.dto.UserDtoWithCredential;
import org.congcong.common.enums.LogLevel;
import org.congcong.common.enums.ProtocolType;
import org.congcong.controlmanager.entity.InboundConfig;
import org.congcong.controlmanager.entity.Route;
import org.congcong.controlmanager.repository.InboundConfigRepository;
import org.congcong.controlmanager.repository.RouteRepository;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AggregateConfigService {
    private final InboundConfigService inboundConfigService;
    private final RouteCompiler routeCompiler;
    private final UserService userService;
    private final RuleSetService ruleSetService;
    private final InboundConfigRepository inboundConfigRepository;
    private final RouteRepository routeRepository;
    private final WorkerConfigDependencyResolver dependencyResolver;
    private final CanonicalConfigHasher canonicalConfigHasher;
    private final FakeIpConfigValidator fakeIpConfigValidator;
    private final org.congcong.controlmanager.repository.ProxyWorkerRepository proxyWorkerRepository;

    /** Builds the minimal runtime configuration for exactly one authenticated worker. */
    public AggregateConfigResponse getAggregateConfig(String workerId) {
        List<InboundConfig> inboundEntities = inboundConfigRepository.findByWorkerIdAndStatusOrderByIdAsc(workerId, 1);
        Set<Long> userIds = dependencyResolver.resolveUserIds(inboundEntities);
        Set<Long> routeIds = dependencyResolver.resolveRouteIds(inboundEntities);

        List<Route> referencedRoutes = routeIds.isEmpty() ? List.of() : routeRepository.findAllById(routeIds);
        if (referencedRoutes.size() != routeIds.size()) {
            throw new IllegalStateException("入站引用了不存在的路由");
        }
        List<Route> routeEntities = referencedRoutes.stream()
                .filter(route -> route.getStatus() != null && route.getStatus() == 1)
                .sorted(Comparator.comparing(Route::getId))
                .toList();
        Map<Long, List<RouteDTO>> compiledRoutes = routeCompiler.compile(routeEntities);
        List<InboundConfigDTO> inbounds = inboundConfigService.getEnabledInboundConfigsForWorker(workerId).stream()
                .map(inbound -> toRuntimeInbound(inbound, compiledRoutes))
                .toList();
        List<RouteDTO> routes = compiledRoutes.values().stream()
                .flatMap(List::stream)
                .sorted(Comparator.comparing(RouteDTO::getId))
                .toList();
        Map<Long, RouteDTO> routesById = routes.stream()
                .collect(Collectors.toMap(RouteDTO::getId, Function.identity()));
        // Validate the effective snapshot, without changing persisted inbound bindings.
        fakeIpConfigValidator.validate(inbounds.stream().map(inbound -> {
            InboundConfig copy = new InboundConfig();
            BeanUtils.copyProperties(inbound, copy);
            return copy;
        }).toList(), routesById::get);

        List<UserDtoWithCredential> users = userService.findUserDTOWithCredentialByStatus(1).stream()
                .filter(user -> userIds.contains(user.getId()))
                .sorted(Comparator.comparing(UserDtoWithCredential::getId))
                .toList();
        if (users.size() != userIds.size()) {
            throw new IllegalStateException("入站引用了不存在或未启用的用户");
        }

        Set<String> ruleSetKeys = dependencyResolver.resolveRuleSetKeys(routeEntities);
        List<RuleSetDTO> ruleSets = ruleSetService.getPublishedRuleSetsWithItems().stream()
                .filter(ruleSet -> ruleSetKeys.contains(ruleSet.getRuleKey()))
                .sorted(Comparator.comparing(RuleSetDTO::getRuleKey))
                .toList();
        if (ruleSets.size() != ruleSetKeys.size()) {
            throw new IllegalStateException("路由引用了不存在或未发布的规则集");
        }

        var worker = proxyWorkerRepository.findById(workerId);
        boolean logReportingEnabled = worker.map(w -> w.getLogReportingEnabled() == null || w.getLogReportingEnabled()).orElse(true);
        LogLevel logLevel = worker.map(w -> w.getLogLevel()).orElse(LogLevel.INFO);
        String configHash = canonicalConfigHasher.hash(inbounds, routes, users, ruleSets, logReportingEnabled, logLevel);
        AggregateConfigResponse response = AggregateConfigResponse.of(workerId, inbounds, routes, users, ruleSets, configHash);
        response.setLogReportingEnabled(logReportingEnabled);
        response.setLogLevel(logLevel);
        return response;
    }

    public String getCurrentConfigHash(String workerId) {
        return getAggregateConfig(workerId).getConfigHash();
    }

    public AggregateConfigResponse getRedactedAggregateConfig(String workerId) {
        AggregateConfigResponse response = getAggregateConfig(workerId);
        response.getUsers().forEach(user -> user.setCredential(null));
        response.getRoutes().forEach(route -> {
            route.setOutboundProxyUsername(null);
            route.setOutboundProxyPassword(null);
            route.setOutboundProxyConfig(null);
        });
        return response;
    }

    /**
     * Drops disabled routes from the bindings and replaces each rewrite table route with the IDs of its
     * expanded entries, in place, so workers see the same match order.
     */
    private InboundConfigDTO toRuntimeInbound(InboundConfigDTO inbound, Map<Long, List<RouteDTO>> compiledRoutes) {
        InboundConfigDTO copy = new InboundConfigDTO();
        BeanUtils.copyProperties(inbound, copy);
        List<RouteDTO> fallback = inbound.getFallbackRouteId() == null ? null : compiledRoutes.get(inbound.getFallbackRouteId());
        if (inbound.getFallbackRouteId() != null && (fallback == null || fallback.size() != 1
                || !inbound.getFallbackRouteId().equals(fallback.get(0).getId()))) {
            copy.setFallbackRouteId(null);
            // Existing workers require a DNS forwarding fallback whenever Fake IP is enabled.
            if (inbound.getProtocol() == ProtocolType.DNS_SERVER) {
                copy.setFakeIpEnabled(false);
            }
        }
        if (inbound.getInboundRouteBindings() != null) {
            copy.setInboundRouteBindings(inbound.getInboundRouteBindings().stream()
                    .filter(Objects::nonNull)
                    .map(binding -> {
                        InboundRouteBinding bindingCopy = new InboundRouteBinding();
                        bindingCopy.setUserIds(binding.getUserIds() == null ? null : new ArrayList<>(binding.getUserIds()));
                        bindingCopy.setRouteIds(binding.getRouteIds() == null ? List.of()
                                : binding.getRouteIds().stream()
                                .flatMap(id -> compiledRoutes.getOrDefault(id, List.of()).stream())
                                .map(RouteDTO::getId)
                                .toList());
                        return bindingCopy;
                    }).toList());
        }
        return copy;
    }
}
