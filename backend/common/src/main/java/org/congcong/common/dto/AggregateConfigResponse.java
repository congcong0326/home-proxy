package org.congcong.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
     * 配置哈希值（用于ETag缓存机制）
     */
    private String configHash;

    /**
     * 创建聚合配置响应
     */
    public static AggregateConfigResponse of(List<InboundConfigDTO> inbounds,
                                           List<RouteDTO> routes,
                                           List<RateLimitDTO> rateLimits,
                                           List<UserDtoWithCredential> users,
                                           String configHash) {
        AggregateConfigResponse response = new AggregateConfigResponse();
        response.setVersion("1.0");
        response.setGeneratedAt(LocalDateTime.now());
        response.setInbounds(inbounds);
        response.setRoutes(routes);
        response.setRateLimits(rateLimits);
        response.setUsers(users);
        response.setConfigHash(configHash);
        return response;
    }
}