package org.congcong.controlmanager.service;

import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.InboundRouteBinding;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.RouteConditionType;
import org.congcong.controlmanager.entity.InboundConfig;
import org.congcong.controlmanager.entity.Route;
import org.congcong.controlmanager.entity.User;
import org.congcong.controlmanager.repository.InboundConfigRepository;
import org.congcong.controlmanager.repository.ProxyWorkerRepository;
import org.congcong.controlmanager.repository.RouteRepository;
import org.congcong.controlmanager.repository.RuleSetRepository;
import org.congcong.controlmanager.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Resolves the JSON references used by an inbound configuration in one place. */
@Service
@RequiredArgsConstructor
public class WorkerConfigDependencyResolver {
    private final InboundConfigRepository inboundConfigRepository;
    private final ProxyWorkerRepository proxyWorkerRepository;
    private final UserRepository userRepository;
    private final RouteRepository routeRepository;
    private final RuleSetRepository ruleSetRepository;

    public Set<Long> resolveUserIds(Collection<InboundConfig> inbounds) {
        Set<Long> ids = new LinkedHashSet<>();
        for (InboundConfig inbound : safe(inbounds)) {
            if (inbound.getAnonymousUserId() != null) ids.add(inbound.getAnonymousUserId());
            for (InboundRouteBinding binding : safe(inbound.getInboundRouteBindings())) {
                if (binding == null) continue;
                ids.addAll(nonNull(binding.getUserIds()));
            }
        }
        return ids;
    }

    public Set<Long> resolveRouteIds(Collection<InboundConfig> inbounds) {
        Set<Long> ids = new LinkedHashSet<>();
        for (InboundConfig inbound : safe(inbounds)) {
            if (inbound.getFallbackRouteId() != null) ids.add(inbound.getFallbackRouteId());
            for (InboundRouteBinding binding : safe(inbound.getInboundRouteBindings())) {
                if (binding == null) continue;
                ids.addAll(nonNull(binding.getRouteIds()));
            }
        }
        return ids;
    }

    public Set<String> resolveRuleSetKeys(Collection<Route> routes) {
        Set<String> keys = new LinkedHashSet<>();
        for (Route route : safe(routes)) {
            if (route == null) continue;
            for (RouteRule rule : safe(route.getRules())) {
                if (rule == null) continue;
                if (rule.getConditionType() == RouteConditionType.RULE_SET && rule.getValue() != null && !rule.getValue().isBlank()) {
                    keys.add(rule.getValue());
                }
            }
        }
        return keys;
    }

    public Set<String> resolveAffectedWorkerIds(String resourceType, Object resourceId) {
        Set<String> result = new LinkedHashSet<>();
        for (InboundConfig inbound : inboundConfigRepository.findAll()) {
            boolean referenced = switch (resourceType) {
                case "user" -> resolveUserIds(List.of(inbound)).contains(resourceId);
                case "route" -> resolveRouteIds(List.of(inbound)).contains(resourceId);
                case "ruleSet" -> inboundReferencesRuleSet(inbound, String.valueOf(resourceId));
                default -> false;
            };
            if (referenced) result.add(inbound.getWorkerId());
        }
        return result;
    }

    public boolean isUserReferenced(Long userId) { return !resolveAffectedWorkerIds("user", userId).isEmpty(); }
    public boolean isRouteReferenced(Long routeId) { return !resolveAffectedWorkerIds("route", routeId).isEmpty(); }
    public boolean isRuleSetReferenced(String key) { return !resolveAffectedWorkerIds("ruleSet", key).isEmpty(); }

    public void validateInboundReferences(InboundConfig inbound) {
        if (inbound.getWorkerId() == null || !proxyWorkerRepository.existsById(inbound.getWorkerId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "节点不存在: " + inbound.getWorkerId());
        }
        validateEnabledUsers(resolveUserIds(List.of(inbound)));
        List<Route> routes = validateEnabledRoutes(resolveRouteIds(List.of(inbound)));
        for (String ruleSetKey : resolveRuleSetKeys(routes)) {
            if (!ruleSetRepository.existsByRuleKeyAndEnabledTrueAndPublishedTrue(ruleSetKey)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "路由引用的规则集不存在、未启用或未发布: " + ruleSetKey);
            }
        }
    }

    private boolean inboundReferencesRuleSet(InboundConfig inbound, String ruleSetKey) {
        Set<Long> routeIds = resolveRouteIds(List.of(inbound));
        return !routeIds.isEmpty() && resolveRuleSetKeys(routeRepository.findAllById(routeIds)).contains(ruleSetKey);
    }

    private void validateEnabledUsers(Set<Long> userIds) {
        if (userIds.isEmpty()) return;
        List<User> users = userRepository.findAllById(userIds);
        if (users.size() != userIds.size() || users.stream().anyMatch(user -> user.getStatus() == null || user.getStatus() != 1)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入站引用的用户不存在或未启用");
        }
    }

    private List<Route> validateEnabledRoutes(Set<Long> routeIds) {
        if (routeIds.isEmpty()) return List.of();
        List<Route> routes = routeRepository.findAllById(routeIds);
        if (routes.size() != routeIds.size() || routes.stream().anyMatch(route -> route.getStatus() == null || route.getStatus() != 1)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入站引用的路由不存在或未启用");
        }
        return routes;
    }

    private <T> Collection<T> safe(Collection<T> values) { return values == null ? List.of() : values; }
    private <T> Set<T> nonNull(Collection<T> values) {
        Set<T> result = new LinkedHashSet<>();
        if (values != null) values.stream().filter(Objects::nonNull).forEach(result::add);
        return result;
    }
}
