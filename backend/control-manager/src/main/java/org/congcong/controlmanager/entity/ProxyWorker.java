package org.congcong.controlmanager.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;
import org.congcong.common.enums.LogLevel;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "proxy_worker")
public class ProxyWorker {
    @Id
    @Column(name = "worker_id", length = 128)
    private String workerId;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "log_reporting_enabled", nullable = false)
    private Boolean logReportingEnabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "log_level", nullable = false, length = 16)
    private LogLevel logLevel = LogLevel.INFO;

    @Column(length = 255)
    private String description;

    @Column(name = "management_url", length = 512)
    private String managementUrl;

    /** Raw token supplied by the worker for the controller-initiated WSS connection. */
    @Column(name = "management_token", length = 128)
    private String managementToken;

    /** Legacy column retained for rolling database compatibility; it is no longer used. */
    @Column(name = "token_hash", columnDefinition = "BINARY(32)")
    private byte[] tokenHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
