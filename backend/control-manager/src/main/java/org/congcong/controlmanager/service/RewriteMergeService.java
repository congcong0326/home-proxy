package org.congcong.controlmanager.service;

import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.InboundRouteBinding;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.MatchOp;
import org.congcong.common.enums.RouteConditionType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.controlmanager.dto.rewrite.RewriteEntryDTO;
import org.congcong.controlmanager.dto.rewrite.RewriteMergePreview;
import org.congcong.controlmanager.dto.rewrite.RewriteMergeRequest;
import org.congcong.controlmanager.dto.rewrite.RewriteTableRequest;
import org.congcong.controlmanager.entity.InboundConfig;
import org.congcong.controlmanager.entity.RewriteEntry;
import org.congcong.controlmanager.entity.Route;
import org.congcong.controlmanager.enums.RouteAction;
import org.congcong.controlmanager.repository.InboundConfigRepository;
import org.congcong.controlmanager.repository.OutboundRepository;
import org.congcong.controlmanager.repository.RewriteEntryRepository;
import org.congcong.controlmanager.repository.RouteRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 把多条固定目标改写路由合并为改写表，见 docs/development/route-outbound-split-phase1-design.md 第 7 节。
 * 被合并路由在每个绑定中第一次出现的位置换成新路由，其余位置移除；旧路由禁用但不删除。
 */
@Service
@RequiredArgsConstructor
public class RewriteMergeService {
    private final RouteRepository routeRepository;
    private final InboundConfigRepository inboundConfigRepository;
    private final OutboundRepository outboundRepository;
    private final RewriteTableService rewriteTableService;
    private final RewriteEntryRepository rewriteEntryRepository;

    @Transactional
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public RewriteMergePreview merge(RewriteMergeRequest request) {
        List<Long> routeIds = new ArrayList<>(new LinkedHashSet<>(request.getRouteIds()));
        Map<Long, Route> routes = routeRepository.findAllById(routeIds).stream()
                .collect(Collectors.toMap(Route::getId, Function.identity()));
        if (routes.size() != routeIds.size()) throw badRequest("部分路由不存在");
        if (routeRepository.existsByName(request.getRouteName().trim())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "路由名称已存在");
        }
        List<InboundConfig> inbounds = inboundConfigRepository.findAll();
        inbounds.sort(Comparator.comparing(InboundConfig::getId));
        for (InboundConfig inbound : inbounds) {
            if (inbound.getFallbackRouteId() != null && routes.containsKey(inbound.getFallbackRouteId())) {
                throw badRequest("路由“" + routes.get(inbound.getFallbackRouteId()).getName() + "”是入站“"
                        + inbound.getName() + "”的兜底路由，不能合并");
            }
        }

        RewriteMergePreview preview = new RewriteMergePreview();
        Set<String> patterns = new HashSet<>();
        for (Long id : routeIds) {
            Route route = routes.get(id);
            validateMergeable(route);
            for (RouteRule rule : route.getRules()) {
                String pattern = rule.getValue().trim().toLowerCase();
                if (!patterns.add(pattern)) throw badRequest("被合并的路由中域名重复: " + pattern);
                RewriteEntryDTO entry = new RewriteEntryDTO();
                entry.setDomainPattern(pattern);
                entry.setTargetHost(route.getRewriteTargetHost());
                entry.setTargetPort(route.getRewriteTargetPort());
                entry.setEnabled(Integer.valueOf(1).equals(route.getStatus()));
                entry.setNotes(route.getName());
                preview.getEntries().add(entry);
            }
        }

        Set<Long> merged = Set.copyOf(routeIds);
        List<Route> allRoutes = routeRepository.findAll();
        Map<Long, String> names = allRoutes.stream().collect(Collectors.toMap(Route::getId, Route::getName));
        Map<Long, List<RouteRule>> conditions = new HashMap<>();
        Map<Long, List<RouteRule>> tableConditions = rewriteEntryRepository.findAll().stream()
                .collect(Collectors.groupingBy(RewriteEntry::getTableId,
                        Collectors.mapping(entry -> domainRule(entry.getDomainPattern()), Collectors.toList())));
        // A rewrite table route matches exactly its table's patterns.
        allRoutes.forEach(route -> conditions.put(route.getId(), route.getRewriteTableId() != null
                ? tableConditions.getOrDefault(route.getRewriteTableId(), List.of())
                : route.getRules()));
        for (InboundConfig inbound : inbounds) {
            List<InboundRouteBinding> bindings = inbound.getInboundRouteBindings();
            for (int index = 0; bindings != null && index < bindings.size(); index++) {
                InboundRouteBinding binding = bindings.get(index);
                if (binding == null || binding.getRouteIds() == null
                        || binding.getRouteIds().stream().noneMatch(merged::contains)) continue;
                preview.getBindings().add(change(inbound, index, binding.getRouteIds(), routeIds, names,
                        conditions, patterns));
            }
        }
        if (request.isDryRun()) return preview;

        RewriteTableRequest tableRequest = new RewriteTableRequest();
        tableRequest.setName(request.getTableName());
        tableRequest.setEntries(preview.getEntries());
        Long tableId = rewriteTableService.createTable(tableRequest).getId();

        Route route = new Route();
        route.setName(request.getRouteName().trim());
        route.setRules(List.of());
        route.setAction(RouteAction.REWRITE_DESTINATION);
        route.setOutboundId(outboundRepository.findByBuiltinKey(OutboundService.BUILTIN_DIRECT)
                .orElseThrow(() -> new IllegalStateException("缺少内置直连出站")).getId());
        route.setRewriteTableId(tableId);
        route.setPolicy(RoutePolicy.DESTINATION_OVERRIDE);
        route.setStatus(1);
        route.setNotes("由路由合并生成：" + routeIds.stream().map(names::get).collect(Collectors.joining("、")));
        Long newRouteId = routeRepository.save(route).getId();

        for (InboundConfig inbound : inbounds) {
            List<InboundRouteBinding> bindings = inbound.getInboundRouteBindings();
            boolean changed = false;
            for (int index = 0; bindings != null && index < bindings.size(); index++) {
                InboundRouteBinding binding = bindings.get(index);
                if (binding == null || binding.getRouteIds() == null
                        || binding.getRouteIds().stream().noneMatch(merged::contains)) continue;
                binding.setRouteIds(replace(binding.getRouteIds(), merged, newRouteId));
                changed = true;
            }
            if (changed) {
                // Reassign so Hibernate detects the change of the JSON column.
                inbound.setInboundRouteBindings(new ArrayList<>(bindings));
                inboundConfigRepository.save(inbound);
            }
        }
        for (Route old : routes.values()) {
            old.setStatus(0);
            routeRepository.save(old);
        }
        preview.getBindings().forEach(change -> change.setAfter(change.getAfter().stream()
                .map(id -> id == null ? newRouteId : id).toList()));
        preview.setTableId(tableId);
        preview.setRouteId(newRouteId);
        return preview;
    }

    private RewriteMergePreview.BindingChange change(InboundConfig inbound, int index, List<Long> before,
                                                     List<Long> mergedOrder, Map<Long, String> names,
                                                     Map<Long, List<RouteRule>> conditions, Set<String> patterns) {
        Set<Long> merged = Set.copyOf(mergedOrder);
        RewriteMergePreview.BindingChange change = new RewriteMergePreview.BindingChange();
        change.setInboundId(inbound.getId());
        change.setInboundName(inbound.getName());
        change.setBindingIndex(index + 1);
        change.setUserIds(inbound.getInboundRouteBindings().get(index).getUserIds());
        change.setBefore(List.copyOf(before));
        change.setAfter(replace(before, merged, null));
        int first = -1;
        int last = -1;
        for (int i = 0; i < before.size(); i++) {
            if (merged.contains(before.get(i))) {
                if (first < 0) first = i;
                last = i;
            }
        }
        for (int i = first + 1; i < last; i++) {
            Long id = before.get(i);
            if (!merged.contains(id) && mayOverlap(conditions.get(id), patterns)) {
                change.getReorderedRoutes().add(names.get(id));
            }
        }
        Set<Long> present = new HashSet<>(before);
        mergedOrder.stream().filter(id -> !present.contains(id)).map(names::get).forEach(change.getGainedRoutes()::add);
        return change;
    }

    /**
     * Conservative check whether a route moved behind the merged entries could match the same hosts. Only routes whose
     * conditions are all plain DOMAIN IN patterns can be proven disjoint; anything else (rule sets, GEO, NOT_IN) counts.
     */
    static boolean mayOverlap(List<RouteRule> rules, Set<String> patterns) {
        if (rules == null) return true;
        for (RouteRule rule : rules) {
            if (rule == null || rule.getConditionType() != RouteConditionType.DOMAIN || rule.getOp() != MatchOp.IN
                    || rule.getValue() == null) {
                return true;
            }
            String base = baseDomain(rule.getValue());
            if (base.isEmpty()) return true;
            for (String pattern : patterns) {
                String other = baseDomain(pattern);
                if (base.equals(other) || base.endsWith("." + other) || other.endsWith("." + base)) return true;
            }
        }
        return false;
    }

    private static RouteRule domainRule(String pattern) {
        RouteRule rule = new RouteRule();
        rule.setConditionType(RouteConditionType.DOMAIN);
        rule.setOp(MatchOp.IN);
        rule.setValue(pattern);
        return rule;
    }

    private static String baseDomain(String pattern) {
        String value = pattern.trim().toLowerCase();
        if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (value.startsWith("*.")) value = value.substring(2);
        else if (value.startsWith(".")) value = value.substring(1);
        return "*".equals(value) ? "" : value;
    }

    private static List<Long> replace(List<Long> routeIds, Set<Long> merged, Long replacement) {
        List<Long> result = new ArrayList<>();
        boolean inserted = false;
        for (Long id : routeIds) {
            if (!merged.contains(id)) {
                result.add(id);
            } else if (!inserted) {
                result.add(replacement);
                inserted = true;
            }
        }
        return result;
    }

    private static void validateMergeable(Route route) {
        if (route.getAction() != RouteAction.REWRITE_DESTINATION || route.getRewriteTableId() != null) {
            throw badRequest("只能合并固定目标的目标改写路由: " + route.getName());
        }
        if (route.getRules() == null || route.getRules().isEmpty() || route.getRules().stream().anyMatch(rule -> rule == null
                || rule.getConditionType() != RouteConditionType.DOMAIN || rule.getOp() != MatchOp.IN
                || rule.getValue() == null || "*".equals(rule.getValue().trim()))) {
            throw badRequest("路由“" + route.getName() + "”的条件不全是具体域名的 DOMAIN IN，不能合并");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
