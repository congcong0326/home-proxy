package org.congcong.controlmanager.service;

import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.RouteDTO;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.MatchOp;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.ProxyEncAlgo;
import org.congcong.common.enums.RouteConditionType;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.controlmanager.dto.route.CreateRouteRequest;
import org.congcong.controlmanager.dto.route.UpdateRouteRequest;
import org.congcong.controlmanager.dto.PageResponse;
import org.congcong.controlmanager.entity.Route;
import org.congcong.controlmanager.repository.RuleSetRepository;
import org.congcong.controlmanager.repository.RouteRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 路由服务层
 */
@Service
@RequiredArgsConstructor
public class RouteService {

    private final RouteRepository routeRepository;
    private final RuleSetRepository ruleSetRepository;

    /**
     * 分页查询路由列表
     */
    public PageResponse<RouteDTO> getRoutes(Pageable pageable, String name, RoutePolicy policy, Integer status) {
        Page<Route> routePage;
        
        // 使用复合条件查询
        routePage = routeRepository.findByConditions(name, policy, status, pageable);
        
        List<RouteDTO> routeDTOs = routePage.getContent().stream()
                .map(this::convertToDTO)
                .toList();
        
        return new PageResponse<>(
                routeDTOs,
                routePage.getTotalElements(),
                routePage.getNumber() + 1,
                routePage.getSize()
        );
    }

    /**
     * 根据ID获取路由详情
     */
    public RouteDTO getRouteById(Long id) {
        Route route = routeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "路由不存在"));
        return convertToDTO(route);
    }

    /**
     * 创建路由
     */
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public RouteDTO createRoute(CreateRouteRequest request) {
        // 检查名称是否已存在
        if (routeRepository.existsByName(request.getName())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "路由名称已存在");
        }
        validateRuleSetReferences(request.getRules(), request.getStatus() != null ? request.getStatus() : 1);
        validateShadowsocksRoute(request.getOutboundProxyType(), request.getOutboundProxyEncAlgo(), request.getOutboundProxyPassword());
        validateOutboundProxy(
                request.getPolicy(),
                request.getOutboundProxyType(),
                request.getOutboundProxyHost(),
                request.getOutboundProxyPort(),
                request.getOutboundProxyConfig());

        Route route = new Route();
        route.setName(request.getName());
        route.setRules(request.getRules());
        route.setPolicy(request.getPolicy());
        route.setOutboundTag(request.getOutboundTag());
        route.setOutboundProxyType(request.getOutboundProxyType());
        route.setOutboundProxyHost(request.getOutboundProxyHost());
        route.setOutboundProxyPort(request.getOutboundProxyPort());
        route.setOutboundProxyUsername(request.getOutboundProxyUsername());
        route.setOutboundProxyPassword(request.getOutboundProxyPassword());
        route.setOutboundProxyEncAlgo(request.getOutboundProxyEncAlgo());
        route.setOutboundProxyConfig(request.getOutboundProxyConfig());
        route.setStatus(request.getStatus() != null ? request.getStatus() : 1);
        route.setNotes(request.getNotes());

        Route savedRoute = routeRepository.save(route);

        return convertToDTO(savedRoute);
    }

    /**
     * 更新路由
     */
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public RouteDTO updateRoute(Long id, UpdateRouteRequest request) {
        Route route = routeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "路由不存在"));

        // 检查名称是否与其他路由冲突
        if (request.getName() != null && !request.getName().equals(route.getName())) {
            if (routeRepository.existsByNameAndIdNot(request.getName(), id)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "路由名称已存在");
            }
            route.setName(request.getName());
        }

        List<RouteRule> targetRules = request.getRules() != null ? request.getRules() : route.getRules();
        Integer targetStatus = request.getStatus() != null ? request.getStatus() : route.getStatus();
        validateRuleSetReferences(targetRules, targetStatus);
        ProtocolType targetProxyType = request.getOutboundProxyType() != null ? request.getOutboundProxyType() : route.getOutboundProxyType();
        ProxyEncAlgo targetProxyEncAlgo = request.getOutboundProxyEncAlgo() != null ? request.getOutboundProxyEncAlgo() : route.getOutboundProxyEncAlgo();
        String targetProxyPassword = request.getOutboundProxyPassword() != null ? request.getOutboundProxyPassword() : route.getOutboundProxyPassword();
        validateShadowsocksRoute(targetProxyType, targetProxyEncAlgo, targetProxyPassword);

        // 更新其他字段
        if (request.getRules() != null) {
            route.setRules(request.getRules());
        }
        if (request.getPolicy() != null) {
            route.setPolicy(request.getPolicy());
        }
        if (request.getOutboundTag() != null) {
            route.setOutboundTag(request.getOutboundTag());
        }
        if (request.getOutboundProxyType() != null) {
            route.setOutboundProxyType(request.getOutboundProxyType());
        }
        if (request.getOutboundProxyHost() != null) {
            route.setOutboundProxyHost(request.getOutboundProxyHost());
        }
        if (request.getOutboundProxyPort() != null) {
            route.setOutboundProxyPort(request.getOutboundProxyPort());
        }
        if (request.getOutboundProxyUsername() != null) {
            route.setOutboundProxyUsername(request.getOutboundProxyUsername());
        }
        if (request.getOutboundProxyPassword() != null) {
            route.setOutboundProxyPassword(request.getOutboundProxyPassword());
        }
        if (request.getOutboundProxyEncAlgo() != null) {
            route.setOutboundProxyEncAlgo(request.getOutboundProxyEncAlgo());
        }
        if (request.getOutboundProxyConfig() != null) {
            route.setOutboundProxyConfig(request.getOutboundProxyConfig());
        }
        if (request.getStatus() != null) {
            route.setStatus(request.getStatus());
        }
        if (request.getNotes() != null) {
            route.setNotes(request.getNotes());
        }
        validateOutboundProxy(
                route.getPolicy(),
                route.getOutboundProxyType(),
                route.getOutboundProxyHost(),
                route.getOutboundProxyPort(),
                route.getOutboundProxyConfig());

        Route savedRoute = routeRepository.save(route);

        return convertToDTO(savedRoute);
    }

    /**
     * 删除路由
     */
    @CacheEvict(value = {"aggregateConfig"}, allEntries = true)
    public void deleteRoute(Long id) {
        Route route = routeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "路由不存在"));

        // TODO: 检查是否被入站配置引用，如果被引用则不能删除
        // if (isRouteReferenced(id)) {
        //     throw new ResponseStatusException(HttpStatus.CONFLICT, "路由正在被使用，无法删除");
        // }

        routeRepository.delete(route);
    }

    /**
     * 获取所有启用的路由（用于下拉选择）
     */
    public List<RouteDTO> getEnabledRoutes() {
        List<Route> routes = routeRepository.findByStatus(1);
        return routes.stream()
                .map(this::convertToDTO)
                .toList();
    }

    /**
     * 转换为DTO
     */
    private RouteDTO convertToDTO(Route route) {
        RouteDTO dto = new RouteDTO();
        dto.setId(route.getId());
        dto.setName(route.getName());
        dto.setRules(route.getRules());
        dto.setPolicy(route.getPolicy());
        dto.setOutboundTag(route.getOutboundTag());
        dto.setOutboundProxyType(route.getOutboundProxyType());
        dto.setOutboundProxyHost(route.getOutboundProxyHost());
        dto.setOutboundProxyPort(route.getOutboundProxyPort());
        dto.setOutboundProxyUsername(route.getOutboundProxyUsername());
        dto.setOutboundProxyPassword(route.getOutboundProxyPassword());
        dto.setOutboundProxyEncAlgo(route.getOutboundProxyEncAlgo());
        dto.setOutboundProxyConfig(route.getOutboundProxyConfig());
        dto.setStatus(route.getStatus());
        dto.setNotes(route.getNotes());
        dto.setCreatedAt(route.getCreatedAt());
        dto.setUpdatedAt(route.getUpdatedAt());
        return dto;
    }

    private void validateOutboundProxy(RoutePolicy policy,
                                       ProtocolType outboundProxyType,
                                       String outboundProxyHost,
                                       Integer outboundProxyPort,
                                       Map<String, Object> outboundProxyConfig) {
        if (policy != RoutePolicy.OUTBOUND_PROXY || outboundProxyType != ProtocolType.VLESS_REALITY) {
            return;
        }
        requireText(outboundProxyHost, "VLESS REALITY outboundProxyHost is required");
        if (outboundProxyPort == null || outboundProxyPort < 1 || outboundProxyPort > 65535) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VLESS REALITY outboundProxyPort must be between 1 and 65535");
        }
        if (outboundProxyConfig == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VLESS REALITY outboundProxyConfig is required");
        }
        requireConfigText(outboundProxyConfig, "serverName", "VLESS REALITY serverName is required");
        requireConfigText(outboundProxyConfig, "publicKey", "VLESS REALITY publicKey is required");
        String shortId = requireConfigText(outboundProxyConfig, "shortId", "VLESS REALITY shortId is required");
        if (!shortId.matches("^[0-9a-fA-F]{0,16}$") || shortId.length() % 2 != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid REALITY shortId hex: " + shortId);
        }
        String uuid = requireConfigText(outboundProxyConfig, "uuid", "VLESS REALITY uuid is required");
        try {
            UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid VLESS uuid: " + uuid, e);
        }
    }

    private String requireConfigText(Map<String, Object> config, String key, String message) {
        Object value = config.get(key);
        return requireText(value == null ? null : String.valueOf(value), message);
    }

    private String requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }


    public void ensureDefaultRouteExists() {
        if (!routeRepository.existsByName("兜底直连路由规则")) {
            Route route = new Route();
            route.setStatus(1);
            route.setName("兜底直连路由规则");
            route.setNotes("兜底直连路由规则，误删除");
            RouteRule routeRule = new RouteRule();
            routeRule.setConditionType(RouteConditionType.DOMAIN);
            routeRule.setOp(MatchOp.IN);
            routeRule.setValue("*");
            route.setRules(Collections.singletonList(routeRule));
            route.setPolicy(RoutePolicy.DIRECT);
            route.setOutboundTag(null);
            route.setOutboundProxyType(null);
            route.setOutboundProxyHost(null);
            route.setOutboundProxyPort(null);
            route.setOutboundProxyUsername(null);
            route.setOutboundProxyPassword(null);
            route.setOutboundProxyEncAlgo(null);
            route.setOutboundProxyConfig(null);
            route.setCreatedAt(null);
            route.setUpdatedAt(null);
            routeRepository.save(route);
        }
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

    private void validateShadowsocksRoute(ProtocolType outboundProxyType, ProxyEncAlgo outboundProxyEncAlgo, String outboundProxyPassword) {
        if (outboundProxyType != ProtocolType.SHADOW_SOCKS) {
            return;
        }
        if (outboundProxyEncAlgo == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Shadowsocks 出站必须指定加密算法");
        }
        if (!outboundProxyEncAlgo.isShadowSocks2022()) {
            return;
        }
        if (outboundProxyPassword == null || outboundProxyPassword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Shadowsocks 2022 出站必须填写 Base64 预共享密钥");
        }
        try {
            String[] keys = outboundProxyPassword.split(":");
            for (String key : keys) {
                byte[] decoded = Base64.getDecoder().decode(key.trim());
                if (decoded.length != outboundProxyEncAlgo.getPskLength()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Shadowsocks 2022 每段预共享密钥都需要是 " + outboundProxyEncAlgo.getPskLength() + " 字节 Base64 数据"
                    );
                }
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Shadowsocks 2022 预共享密钥必须是有效的 Base64 字符串，支持 iPSK:uPSK 形式");
        }
    }
}
