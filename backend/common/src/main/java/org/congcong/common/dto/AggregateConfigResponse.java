package org.congcong.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.congcong.common.enums.LogLevel;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 聚合配置响应DTO
 * 用于配置分发机制，包含所有启用的配置信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AggregateConfigResponse {
    /** Worker that is authorized to consume this response. */
    private String workerId;
    /** 是否启用代理网关 V1 日志采集与上报，默认开启；旧版日志固定停用。 */
    private Boolean logReportingEnabled = true;
    /** Worker runtime logging level; configurations without this field use INFO. */
    private LogLevel logLevel = LogLevel.INFO;
    /**
     * 配置版本
     */
    private String version;

    /**
     * 配置生成时间
     */
    private LocalDateTime generatedAt;

    /**
     * 入站配置列表
     */
    private List<InboundConfigDTO> inbounds;

    /**
     * 路由配置列表
     */
    private List<RouteDTO> routes;

    /**
     * 限流配置列表
     */
    private List<RateLimitDTO> rateLimits;

    /**
     * 用户配置列表
     */
    private List<UserDtoWithCredential> users;

    /**
     * dns规则
     */
    private List<DnsRuleDTO> dnsRules;

    /**
     * 已发布的规则集
     */
    private List<RuleSetDTO> ruleSets;

    /**
     * 配置哈希值（用于ETag缓存机制）
     */
    private String configHash;

    /**
     * 创建聚合配置响应
     */
    public static AggregateConfigResponse of(String workerId,
                                           List<InboundConfigDTO> inbounds,
                                           List<RouteDTO> routes,
                                           List<RateLimitDTO> rateLimits,
                                           List<UserDtoWithCredential> users,
                                           List<RuleSetDTO> ruleSets,
                                           String configHash) {
        AggregateConfigResponse response = new AggregateConfigResponse();
        response.setWorkerId(workerId);
        response.setVersion("2.0");
        response.setGeneratedAt(LocalDateTime.now());
        response.setInbounds(inbounds);
        response.setRoutes(routes);
        response.setRateLimits(rateLimits);
        response.setUsers(users);
        response.setRuleSets(ruleSets);
        response.setConfigHash(configHash);
        return response;
    }

    /** Compatibility helper for callers that are upgraded independently. */
    public static AggregateConfigResponse of(List<InboundConfigDTO> inbounds,
                                           List<RouteDTO> routes,
                                           List<RateLimitDTO> rateLimits,
                                           List<UserDtoWithCredential> users,
                                           List<RuleSetDTO> ruleSets,
                                           String configHash) {
        return of(null, inbounds, routes, rateLimits, users, ruleSets, configHash);
    }
}
