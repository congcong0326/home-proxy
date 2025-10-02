package org.congcong.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 路由配置DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RouteDto {
    /**
     * 路由ID
     */
    private String id;

    /**
     * 路由名称
     */
    private String name;

    /**
     * 路由规则JSON
     */
    private String rulesJson;

    /**
     * 路由策略
     */
    private String policy;

    /**
     * 出站标签
     */
    private String outboundTag;

    /**
     * 出站代理类型
     */
    private String outboundProxyType;

    /**
     * 出站代理主机
     */
    private String outboundProxyHost;

    /**
     * 出站代理端口
     */
    private Integer outboundProxyPort;

    /**
     * 出站代理用户名
     */
    private String outboundProxyUsername;

    /**
     * 出站代理密码
     */
    private String outboundProxyPassword;

    /**
     * 出站代理是否启用TLS
     */
    private Boolean outboundProxyTls;

    /**
     * 状态（1-启用，0-禁用）
     */
    private Integer status;

    /**
     * 备注
     */
    private String notes;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}