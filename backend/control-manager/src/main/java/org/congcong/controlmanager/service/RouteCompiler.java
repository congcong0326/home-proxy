package org.congcong.controlmanager.service;

import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.RouteDTO;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.MatchOp;
import org.congcong.common.enums.RouteConditionType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.controlmanager.entity.Outbound;
import org.congcong.controlmanager.entity.RewriteEntry;
import org.congcong.controlmanager.entity.Route;
import org.congcong.controlmanager.enums.OutboundKind;
import org.congcong.controlmanager.enums.RouteAction;
import org.congcong.controlmanager.repository.OutboundRepository;
import org.congcong.controlmanager.repository.RewriteEntryRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compiles control-plane routes (route + outbound + rewrite table) into the {@link RouteDTO} list workers consume.
 * Workers are unaware of outbounds and rewrite tables; see docs/development/route-outbound-split-phase1-design.md.
 */
@Component
@RequiredArgsConstructor
public class RouteCompiler {
    /** Synthetic route IDs for rewrite table entries; far above AUTOINCREMENT values and positive for telemetry. */
    public static final long REWRITE_ROUTE_ID_BASE = 1_000_000_000_000L;

    private final OutboundRepository outboundRepository;
    private final RewriteEntryRepository rewriteEntryRepository;

    /** Maps each route ID to the worker routes it expands to, in match order. */
    public Map<Long, List<RouteDTO>> compile(Collection<Route> routes) {
        Map<Long, Outbound> outbounds = outboundRepository.findAllById(routes.stream()
                        .map(Route::getOutboundId).filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Outbound::getId, Function.identity()));
        Map<Long, List<RewriteEntry>> entries = rewriteEntryRepository.findByTableIdInOrderBySortOrderAscIdAsc(
                        routes.stream().map(Route::getRewriteTableId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.groupingBy(RewriteEntry::getTableId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<RouteDTO>> compiled = new LinkedHashMap<>();
        for (Route route : routes) {
            compiled.put(route.getId(), compile(route, outbounds, entries.getOrDefault(route.getRewriteTableId(), List.of())));
        }
        return compiled;
    }

    /**
     * Compiles one route without expanding its rewrite table: table routes become a single rule-less
     * DESTINATION_OVERRIDE. Used by validations that only care about the route's policy and conditions.
     */
    public RouteDTO compileHead(Route route) {
        if (route.getRewriteTableId() != null) {
            RouteDTO dto = base(route);
            dto.setRules(List.of());
            dto.setPolicy(RoutePolicy.DESTINATION_OVERRIDE);
            return dto;
        }
        Map<Long, Outbound> outbounds = route.getOutboundId() == null ? Map.of()
                : outboundRepository.findById(route.getOutboundId()).map(o -> Map.of(o.getId(), o)).orElse(Map.of());
        return compile(route, outbounds, List.of()).get(0);
    }

    /** The legacy policy column value for a route, kept in sync for listing and troubleshooting. */
    public static RoutePolicy policyOf(RouteAction action, OutboundKind outboundKind) {
        return switch (action) {
            case FORWARD -> switch (outboundKind) {
                case DIRECT -> RoutePolicy.DIRECT;
                case BLOCK -> RoutePolicy.BLOCK;
                case PROXY, DNS_UPSTREAM -> RoutePolicy.OUTBOUND_PROXY;
            };
            case REWRITE_DESTINATION -> RoutePolicy.DESTINATION_OVERRIDE;
            case REWRITE_DNS -> RoutePolicy.DNS_REWRITING;
        };
    }

    public static long rewriteRouteId(RewriteEntry entry) {
        return REWRITE_ROUTE_ID_BASE + entry.getId();
    }

    private List<RouteDTO> compile(Route route, Map<Long, Outbound> outbounds, List<RewriteEntry> entries) {
        RouteAction action = route.getAction();
        if (action == null) throw new IllegalStateException("路由缺少动作: " + route.getName());
        return switch (action) {
            case FORWARD -> List.of(forward(route, outbounds.get(route.getOutboundId())));
            case REWRITE_DESTINATION -> route.getRewriteTableId() != null
                    ? expandTable(route, entries)
                    : List.of(rewrite(route, RoutePolicy.DESTINATION_OVERRIDE, route.getRewriteTargetPort()));
            case REWRITE_DNS -> List.of(rewrite(route, RoutePolicy.DNS_REWRITING, null));
        };
    }

    private RouteDTO forward(Route route, Outbound outbound) {
        if (outbound == null) {
            throw new IllegalStateException("路由引用的出站不存在: " + route.getName());
        }
        RouteDTO dto = base(route);
        dto.setPolicy(policyOf(RouteAction.FORWARD, outbound.getKind()));
        if (dto.getPolicy() == RoutePolicy.OUTBOUND_PROXY) {
            dto.setOutboundProxyType(outbound.getProxyType());
            dto.setOutboundProxyHost(outbound.getHost());
            dto.setOutboundProxyPort(outbound.getPort());
            dto.setOutboundProxyUsername(outbound.getUsername());
            dto.setOutboundProxyPassword(outbound.getPassword());
            dto.setOutboundProxyEncAlgo(outbound.getEncAlgo());
            dto.setOutboundProxyConfig(outbound.getConfig());
        }
        return dto;
    }

    private RouteDTO rewrite(Route route, RoutePolicy policy, Integer port) {
        RouteDTO dto = base(route);
        dto.setPolicy(policy);
        dto.setOutboundProxyHost(route.getRewriteTargetHost());
        dto.setOutboundProxyPort(port);
        return dto;
    }

    private List<RouteDTO> expandTable(Route route, List<RewriteEntry> entries) {
        List<RouteDTO> expanded = new ArrayList<>();
        for (RewriteEntry entry : entries) {
            if (!Boolean.TRUE.equals(entry.getEnabled())) continue;
            RouteDTO dto = base(route);
            dto.setId(rewriteRouteId(entry));
            dto.setName(route.getName() + " / " + entry.getDomainPattern());
            RouteRule rule = new RouteRule();
            rule.setConditionType(RouteConditionType.DOMAIN);
            rule.setOp(MatchOp.IN);
            rule.setValue(entry.getDomainPattern());
            dto.setRules(List.of(rule));
            dto.setPolicy(RoutePolicy.DESTINATION_OVERRIDE);
            dto.setOutboundProxyHost(entry.getTargetHost());
            dto.setOutboundProxyPort(entry.getTargetPort());
            expanded.add(dto);
        }
        return expanded;
    }

    private RouteDTO base(Route route) {
        RouteDTO dto = new RouteDTO();
        dto.setId(route.getId());
        dto.setName(route.getName());
        dto.setRules(route.getRules());
        dto.setOutboundTag(route.getOutboundTag());
        dto.setStatus(route.getStatus());
        dto.setNotes(route.getNotes());
        dto.setCreatedAt(route.getCreatedAt());
        dto.setUpdatedAt(route.getUpdatedAt());
        return dto;
    }
}
