package org.congcong.controlmanager.entity.disk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

@Data
@Entity
@Table(name = "disk_monitor_setting")
public class DiskMonitorSettingEntity {

    @Id
    @Column(name = "setting_key", nullable = false, length = 64)
    private String settingKey;

    @Column(name = "setting_value", nullable = false, length = 512)
    private String settingValue;

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
