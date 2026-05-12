package org.congcong.controlmanager.entity.disk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

@Data
@Entity
@Table(name = "disk_monitor_host", indexes = {
        @Index(name = "idx_disk_monitor_host_last_seen", columnList = "last_seen_at")
})
public class DiskMonitorHostEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "host_id", nullable = false, unique = true, length = 128)
    private String hostId;

    @Column(name = "host_name", length = 255)
    private String hostName;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "last_source_ip", length = 64)
    private String lastSourceIp;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}
