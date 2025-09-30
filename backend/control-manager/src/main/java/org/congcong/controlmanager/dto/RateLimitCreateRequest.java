package org.congcong.controlmanager.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.congcong.common.enums.RateLimitScopeType;


import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 创建限流策略请求DTO
 */
@Data
public class RateLimitCreateRequest {

    /**
     * 范围类型
     */
    @NotNull(message = "范围类型不能为空")
    private RateLimitScopeType scopeType;

    /**
     * 用户ID列表（当scopeType为USERS时必填）
     */
    private List<Long> userIds;

    /**
     * 上行带宽限制（bps）
     */
    @Positive(message = "上行带宽限制必须大于0")
    private Long uplinkLimitBps;

    /**
     * 下行带宽限制（bps）
     */
    @Positive(message = "下行带宽限制必须大于0")
    private Long downlinkLimitBps;

    /**
     * 突发字节数
     */
    @Positive(message = "突发字节数必须大于0")
    private Long burstBytes;

    /**
     * 是否启用
     */
    @NotNull(message = "启用状态不能为空")
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
}