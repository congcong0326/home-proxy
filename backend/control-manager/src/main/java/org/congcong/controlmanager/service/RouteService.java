package org.congcong.controlmanager.service;

import com.google.common.net.InetAddresses;
import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.InboundRouteBinding;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.MatchOp;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RouteConditionType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.controlmanager.dto.PageResponse;
import org.congcong.controlmanager.dto.route.RouteRequest;
import org.congcong.controlmanager.dto.route.RouteView;
import org.congcong.controlmanager.entity.InboundConfig;
import org.congcong.controlmanager.entity.Outbound;
import org.congcong.controlmanager.entity.RewriteTable;
import org.congcong.controlmanager.entity.Route;
import org.congcong.controlmanager.enums.OutboundKind;
import org.congcong.controlmanager.enums.RouteAction;
import org.congcong.controlmanager.repository.OutboundRepository;
import org.congcong.controlmanager.repository.RewriteEntryRepository;
import org.congcong.controlmanager.repository.RewriteTableRepository;
import org.congcong.controlmanager.repository.RouteRepository;
import org.congcong.controlmanager.repository.RuleSetRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 路由服务层。路由只描述条件与动作，出站参数在 {@link OutboundService}，改写条目在 {@link RewriteTableService}。
 */
@Service
@RequiredArgsConstructor
public class RouteService {
    static final String DEFAULT_ROUTE_NAME = "兜底直连路由规则";

    private final RouteRepository routeRepository;
    private final RuleSetRepository ruleSetRepository;
    private final WorkerConfigDependencyResolver workerConfigDependencyResolver;
    private final OutboundRepository outboundRepository;
    private final RewriteTableRepository rewriteTableRepository;
    private final RewriteEntryRepository rewriteEntryRepository;

    /**
     * 分页查询路由列表
     */
    public PageResponse<RouteView> getRoutes(Pageable pageable, String name, RoutePolicy policy, Integer status) {
        Page<Route> routePage = routeRepository.findByConditions(name, policy, status, pageable);
        return new PageResponse<>(
                toViews(routePage.getContent()),
                routePage.getTotalElements(),
                routePage.getNumber() + 1,
                routePage.getSize()
        );
    }

    /**
     * 根据ID获取路由详情
     */
    public RouteView getRouteById(Long id) {
        return toViews(List.of(require(id))).get(0);
    }

    /**
     * 获取所有启用的路由（用于下拉选择）
     */
    public List<RouteView> getEnabledRoutes() {
        return toViews(routeRepository.findByStatus(1));
    }

    /**
     * 创建路由
     */
    @Transactional
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public RouteView createRoute(RouteRequest request) {
        if (routeRepository.existsByName(request.getName().trim())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "路由名称已存在");
        }
        Route route = new Route();
        apply(route, request);
        return toViews(List.of(routeRepository.save(route))).get(0);
    }

    /**
     * 更新路由。请求为整体替换，字段组合按动作重新校验。
     */
    @Transactional
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public RouteView updateRoute(Long id, RouteRequest request) {
        Route route = require(id);
        if (routeRepository.existsByNameAndIdNot(request.getName().trim(), id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "路由名称已存在");
        }
        if (request.getRewriteTableId() != null && workerConfigDependencyResolver.isFallbackRoute(id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该路由被用作入站的兜底路由，不能改为引用改写表");
        }
        apply(route, request);
        return toViews(List.of(routeRepository.save(route))).get(0);
    }

    /**
     * 删除路由
     */
    @Transactional
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public void deleteRoute(Long id) {
        Route route = routeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "路由不存在"));

        List<InboundConfig> references = workerConfigDependencyResolver.findInboundsReferencingRoute(id);
        if (!references.isEmpty()) {
            String details = references.stream()
                    .map(inbound -> describeRouteReference(inbound, id))
                    .collect(Collectors.joining("\n"));
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "路由“" + route.getName() + "”（ID：" + id + "）被以下入站配置引用，无法删除：\n"
                            + details
                            + "\n请前往「入站配置」编辑以上配置，在对应的「路由顺序」中移除此路由，"
                            + "或更换／清空「兜底路由」，保存后重试。禁用的入站配置也需要解除引用。");
        }

        routeRepository.delete(route);
    }

    private String describeRouteReference(InboundConfig inbound, Long routeId) {
        List<String> locations = new ArrayList<>();
        if (routeId.equals(inbound.getFallbackRouteId())) {
            locations.add("兜底路由");
        }
        List<InboundRouteBinding> bindings = inbound.getInboundRouteBindings();
        if (bindings != null) {
            for (int index = 0; index < bindings.size(); index++) {
                InboundRouteBinding binding = bindings.get(index);
                if (binding != null && binding.getRouteIds() != null && binding.getRouteIds().contains(routeId)) {
                    locations.add(inbound.getProtocol() == ProtocolType.SHADOW_SOCKS
                            ? "绑定关系 → 路由顺序"
                            : "绑定组 #" + (index + 1) + " → 路由顺序");
                }
            }
        }
        String listenIp = inbound.getListenIp();
        String address = listenIp != null && listenIp.contains(":") ? "[" + listenIp + "]" : listenIp;
        return "• " + inbound.getName() + "（ID：" + inbound.getId() + "，节点：" + inbound.getWorkerId()
                + "，监听：" + address + ":" + inbound.getPort()
                + (Integer.valueOf(0).equals(inbound.getStatus()) ? "，已禁用" : "")
                + "）：" + String.join("、", locations);
    }

    public void ensureDefaultRouteExists() {
        if (routeRepository.existsByName(DEFAULT_ROUTE_NAME)) {
            return;
        }
        Outbound direct = outboundRepository.findByBuiltinKey(OutboundService.BUILTIN_DIRECT)
                .orElseThrow(() -> new IllegalStateException("缺少内置直连出站"));
        Route route = new Route();
        route.setStatus(1);
        route.setName(DEFAULT_ROUTE_NAME);
        route.setNotes("兜底直连路由规则，误删除");
        RouteRule routeRule = new RouteRule();
        routeRule.setConditionType(RouteConditionType.DOMAIN);
        routeRule.setOp(MatchOp.IN);
        routeRule.setValue("*");
        route.setRules(Collections.singletonList(routeRule));
        route.setAction(RouteAction.FORWARD);
        route.setOutboundId(direct.getId());
        route.setPolicy(RoutePolicy.DIRECT);
        routeRepository.save(route);
    }

    private Route require(Long id) {
        return routeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "路由不存在"));
    }

    private void apply(Route route, RouteRequest request) {
        RouteAction action = request.getAction();
        List<RouteRule> rules = request.getRules() == null ? List.of() : request.getRules();
        Integer status = request.getStatus() != null ? request.getStatus()
                : route.getStatus() != null ? route.getStatus() : 1;
        Long outboundId = null;
        Long rewriteTableId = null;
        String targetHost = null;
        Integer targetPort = null;
        OutboundKind outboundKind = OutboundKind.DIRECT;
        switch (action) {
            case FORWARD -> {
                requireRules(rules);
                if (request.getOutboundId() == null) throw badRequest("转发路由必须选择出站");
                Outbound outbound = outboundRepository.findById(request.getOutboundId())
                        .orElseThrow(() -> badRequest("出站不存在: " + request.getOutboundId()));
                outboundId = outbound.getId();
                outboundKind = outbound.getKind();
            }
            case REWRITE_DESTINATION -> {
                // Workers connect overrides directly, so the outbound is pinned to the built-in DIRECT.
                outboundId = outboundRepository.findByBuiltinKey(OutboundService.BUILTIN_DIRECT)
                        .orElseThrow(() -> new IllegalStateException("缺少内置直连出站")).getId();
                if (request.getRewriteTableId() != null) {
                    if (!rules.isEmpty()) throw badRequest("引用改写表的路由不能再配置条件，表中的域名即匹配条件");
                    if (request.getRewriteTargetHost() != null && !request.getRewriteTargetHost().isBlank()) {
                        throw badRequest("改写表与固定目标只能二选一");
                    }
                    if (!rewriteTableRepository.existsById(request.getRewriteTableId())) {
                        throw badRequest("改写表不存在: " + request.getRewriteTableId());
                    }
                    rewriteTableId = request.getRewriteTableId();
                } else {
                    requireRules(rules);
                    targetHost = requireText(request.getRewriteTargetHost(), "目标改写必须填写改写目标或选择改写表");
                    targetPort = request.getRewriteTargetPort();
                    if (targetPort == null || targetPort < 1 || targetPort > 65535) {
                        throw badRequest("改写端口必须在 1-65535 之间");
                    }
                }
            }
            case REWRITE_DNS -> {
                requireRules(rules);
                targetHost = requireText(request.getRewriteTargetHost(), "DNS 改写必须填写应答 IP");
                if (!InetAddresses.isInetAddress(targetHost)) throw badRequest("DNS 改写的应答必须是 IP 地址: " + targetHost);
            }
        }
        validateAdBlockConditionDisabled(rules);
        validateRuleSetReferences(rules, status);

        route.setName(request.getName().trim());
        route.setRules(new ArrayList<>(rules));
        route.setAction(action);
        route.setOutboundId(outboundId);
        route.setRewriteTableId(rewriteTableId);
        route.setRewriteTargetHost(targetHost);
        route.setRewriteTargetPort(targetPort);
        route.setPolicy(RouteCompiler.policyOf(action, outboundKind));
        route.setStatus(status);
        route.setNotes(request.getNotes());
    }

    private List<RouteView> toViews(List<Route> routes) {
        Map<Long, Outbound> outbounds = outboundRepository.findAllById(routes.stream()
                        .map(Route::getOutboundId).filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Outbound::getId, Function.identity()));
        Map<Long, RewriteTable> tables = rewriteTableRepository.findAllById(routes.stream()
                        .map(Route::getRewriteTableId).filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(RewriteTable::getId, Function.identity()));
        return routes.stream().map(route -> {
            RouteView view = new RouteView();
            view.setId(route.getId());
            view.setName(route.getName());
            view.setRules(route.getRules());
            view.setAction(route.getAction());
            view.setOutboundId(route.getOutboundId());
            Outbound outbound = outbounds.get(route.getOutboundId());
            if (outbound != null) {
                view.setOutboundName(outbound.getName());
                view.setOutboundKind(outbound.getKind());
            }
            view.setRewriteTableId(route.getRewriteTableId());
            RewriteTable table = tables.get(route.getRewriteTableId());
            if (table != null) {
                view.setRewriteTableName(table.getName());
                view.setRewriteEntryCount(rewriteEntryRepository.countByTableId(table.getId()));
            }
            view.setRewriteTargetHost(route.getRewriteTargetHost());
            view.setRewriteTargetPort(route.getRewriteTargetPort());
            view.setPolicy(route.getPolicy());
            view.setOutboundTag(route.getOutboundTag());
            view.setStatus(route.getStatus());
            view.setNotes(route.getNotes());
            view.setCreatedAt(route.getCreatedAt());
            view.setUpdatedAt(route.getUpdatedAt());
            return view;
        }).toList();
    }

    private static void requireRules(List<RouteRule> rules) {
        if (rules.isEmpty()) throw badRequest("路由规则不能为空");
    }

    private static String requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) throw badRequest(message);
        return value.trim();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private void validateRuleSetReferences(List<RouteRule> rules, Integer routeStatus) {
        if (rules == null) {
            return;
        }
        boolean routeEnabled = routeStatus == null || routeStatus == 1;
        for (RouteRule rule : rules) {
            if (rule == null || rule.getConditionType() != RouteConditionType.RULE_SET) {
                continue;
            }
            String ruleSetKey = rule.getValue() == null ? null : rule.getValue().trim();
            if (ruleSetKey == null || ruleSetKey.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RULE_SET 路由规则必须指定规则集 key");
            }
            if (!ruleSetRepository.existsByRuleKey(ruleSetKey)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "引用的规则集不存在: " + ruleSetKey);
            }
            if (routeEnabled && !ruleSetRepository.existsByRuleKeyAndEnabledTrueAndPublishedTrue(ruleSetKey)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "启用中的路由只能引用已启用且已发布的规则集: " + ruleSetKey);
            }
        }
    }

    private void validateAdBlockConditionDisabled(List<RouteRule> rules) {
        if (rules == null) {
            return;
        }
        for (RouteRule rule : rules) {
            if (rule != null && rule.getConditionType() == RouteConditionType.AD_BLOCK) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AD_BLOCK 路由条件已下线，请使用 RULE_SET 规则集替代");
            }
        }
    }
}
