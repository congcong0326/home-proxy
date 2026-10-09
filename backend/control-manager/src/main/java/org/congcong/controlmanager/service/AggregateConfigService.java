package org.congcong.controlmanager.service;

import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.AggregateConfigResponse;
import org.congcong.common.dto.InboundConfigDTO;
import org.congcong.common.dto.RateLimitDTO;
import org.congcong.common.dto.RouteDTO;
import org.congcong.common.dto.RuleSetDTO;
import org.congcong.common.dto.UserDtoWithCredential;
import org.congcong.common.enums.RateLimitScopeType;
import org.congcong.common.enums.LogLevel;
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
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AggregateConfigService {
    private final InboundConfigService inboundConfigService;
    private final RateLimitService rateLimitService;
    private final RouteService routeService;
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
        fakeIpConfigValidator.validate(inboundEntities);
        List<InboundConfigDTO> inbounds = inboundConfigService.getEnabledInboundConfigsForWorker(workerId);
        Set<Long> userIds = dependencyResolver.resolveUserIds(inboundEntities);
        Set<Long> routeIds = dependencyResolver.resolveRouteIds(inboundEntities);

        List<Route> routeEntities = routeIds.isEmpty() ? List.of() : routeRepository.findAllById(routeIds).stream()
                .filter(route -> route.getStatus() != null && route.getStatus() == 1)
                .sorted(Comparator.comparing(Route::getId))
                .toList();
        if (routeEntities.size() != routeIds.size()) {
            throw new IllegalStateException("入站引用了不存在或未启用的路由");
        }

        List<RouteDTO> routes = routeService.getEnabledRoutes().stream()
                .filter(route -> routeIds.contains(route.getId()))
                .sorted(Comparator.comparing(RouteDTO::getId))
                .toList();
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

        List<RateLimitDTO> rateLimits = filterRateLimits(rateLimitService.getRateLimitsByEnabled(true), userIds);
        var worker = proxyWorkerRepository.findById(workerId);
        boolean logReportingEnabled = worker.map(w -> w.getLogReportingEnabled() == null || w.getLogReportingEnabled()).orElse(true);
        LogLevel logLevel = worker.map(w -> w.getLogLevel()).orElse(LogLevel.INFO);
        String configHash = canonicalConfigHasher.hash(inbounds, routes, rateLimits, users, ruleSets, logReportingEnabled, logLevel);
        AggregateConfigResponse response = AggregateConfigResponse.of(workerId, inbounds, routes, rateLimits, users, ruleSets, configHash);
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

    private List<RateLimitDTO> filterRateLimits(List<RateLimitDTO> all, Set<Long> userIds) {
        List<RateLimitDTO> result = new ArrayList<>();
        for (RateLimitDTO rateLimit : all) {
            if (rateLimit.getScopeType() == RateLimitScopeType.GLOBAL) {
                result.add(rateLimit);
            } else if (rateLimit.getScopeType() == RateLimitScopeType.USERS && rateLimit.getUserIds() != null) {
                List<Long> included = rateLimit.getUserIds().stream().filter(userIds::contains).toList();
                if (!included.isEmpty()) {
                    RateLimitDTO copy = new RateLimitDTO();
                    BeanUtils.copyProperties(rateLimit, copy);
                    copy.setUserIds(included);
                    result.add(copy);
                }
            }
        }
        return result.stream().sorted(Comparator.comparing(RateLimitDTO::getId)).toList();
    }
}
