package org.congcong.controlmanager.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.congcong.common.enums.RateLimitScopeType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Data
@Entity
@Table(name = "rate_limits")
public class RateLimit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scope_type")
    @Enumerated(EnumType.STRING)
    private RateLimitScopeType scopeType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "user_ids", columnDefinition = "JSON")
    private List<Long> userIds;

    @Column(name = "uplink_limit_bps")
    private Long uplinkLimitBps;

    @Column(name = "downlink_limit_bps")
    private Long downlinkLimitBps;

    @Column(name = "burst_bytes")
    private Long burstBytes;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "effective_time_start")
    private LocalTime effectiveTimeStart;

    @Column(name = "effective_time_end")
    private LocalTime effectiveTimeEnd;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}