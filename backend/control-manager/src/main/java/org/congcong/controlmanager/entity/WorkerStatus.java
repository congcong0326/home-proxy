package org.congcong.controlmanager.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "worker_status")
public class WorkerStatus {

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
    private String lastConfigHash;

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
}
