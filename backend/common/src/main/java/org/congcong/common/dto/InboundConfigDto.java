package org.congcong.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 入站配置DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InboundConfigDto {
    /**
     * 入站配置ID
     */
    private String id;

    /**
     * 配置名称
     */
    private String name;

    /**
     * 协议类型
     */
    private String protocol;

    /**
     * 监听IP
     */
    private String listenIp;

    /**
     * 监听端口
     */
    private Integer port;

    /**
     * 是否启用TLS
     */
    private Boolean tlsEnabled;

    /**
     * 是否启用协议嗅探
     */
    private Boolean sniffEnabled;

    /**
     * Shadowsocks加密方法
     */
    private String ssMethod;

    /**
     * 允许的用户ID列表
     */
    private List<Long> allowedUserIds;

    /**
     * 路由ID列表
     */
    private List<Long> routeIds;

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