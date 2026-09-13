package org.congcong.controlmanager.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "worker_status")
public class WorkerStatus {
    private static final String EMPTY_CAPABILITIES_JSON = "[]";

    @Id
    @Column(name = "worker_id", length = 128)
    private String workerId;

    @Column(name = "hostname", length = 255)
    private String hostname;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "last_config_hash", length = 128)
    private String fetchedConfigHash;

    @Column(name = "applied_config_hash", length = 128)
    private String appliedConfigHash;

    @Column(name = "apply_success")
    private Boolean applySuccess;

    @Column(name = "apply_error", length = 1024)
    private String applyError;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    @Column(name = "capabilities_json", nullable = false, columnDefinition = "json")
    private String capabilitiesJson = EMPTY_CAPABILITIES_JSON;

    @Column(name = "uptime_seconds")
    private Long uptimeSeconds;

    @Column(name = "heap_used_bytes")
    private Long heapUsedBytes;

    @Column(name = "heap_max_bytes")
    private Long heapMaxBytes;

    @Column(name = "running_inbound_count")
    private Integer runningInboundCount;

    @Column(name = "active_connection_count")
    private Integer activeConnectionCount;

    @PrePersist
    @PreUpdate
    void ensureCapabilitiesJson() {
        if (capabilitiesJson == null || capabilitiesJson.isBlank()) {
            capabilitiesJson = EMPTY_CAPABILITIES_JSON;
        }
    }
}
