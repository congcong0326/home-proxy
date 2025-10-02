package org.congcong.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 限流配置DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitDto {
    /**
     * 限流配置ID
     */
    private String id;

    /**
     * 作用域类型（global-全局，users-指定用户）
     */
    private String scopeType;

    /**
     * 用户ID列表（当scopeType为users时使用）
     */
    private List<String> userIds;

    /**
     * 上行带宽限制（bps）
     */
    private Long uplinkLimitBps;

    /**
     * 下行带宽限制（bps）
     */
    private Long downlinkLimitBps;

    /**
     * 突发字节数
     */
    private Long burstBytes;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 每日生效开始时间
     */
    private LocalTime effectiveTimeStart;

    /**
     * 每日生效结束时间
     */
    private LocalTime effectiveTimeEnd;

    /**
     * 生效开始日期
     */
    private LocalDate effectiveFrom;

    /**
     * 生效结束日期
     */
    private LocalDate effectiveTo;

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