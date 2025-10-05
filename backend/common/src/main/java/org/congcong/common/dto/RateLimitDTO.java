package org.congcong.common.dto;

import lombok.Data;
import org.congcong.common.enums.RateLimitScopeType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Data
public class RateLimitDTO {
    private Long id;
    private RateLimitScopeType scopeType;
    private List<Long> userIds;
    private Long uplinkLimitBps;
    private Long downlinkLimitBps;
    private Long burstBytes;
    private Boolean enabled;
    private LocalTime effectiveTimeStart;
    private LocalTime effectiveTimeEnd;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}