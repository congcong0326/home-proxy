package org.congcong.controlmanager.entity.disk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

@Data
@Entity
@Table(name = "disk_monitor_sample", indexes = {
        @Index(name = "idx_disk_monitor_sample_host_device_time", columnList = "host_id,device,sampled_at"),
        @Index(name = "idx_disk_monitor_sample_time", columnList = "sampled_at")
})
public class DiskMonitorSampleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "host_id", nullable = false, length = 128)
    private String hostId;

    @Column(nullable = false, length = 128)
    private String device;

    @Column(name = "sampled_at", nullable = false)
    private Instant sampledAt;

    @Column(name = "detail_json", nullable = false, columnDefinition = "JSON")
    private String detailJson;

    @Column(name = "raw_smart_output", nullable = false, columnDefinition = "LONGTEXT")
    private String rawSmartOutput;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = Instant.now();
    }
}
