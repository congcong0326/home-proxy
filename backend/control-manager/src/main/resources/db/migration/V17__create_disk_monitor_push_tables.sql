CREATE TABLE disk_monitor_host (
    id BIGINT NOT NULL AUTO_INCREMENT,
    host_id VARCHAR(128) NOT NULL,
    host_name VARCHAR(255) NULL,
    last_seen_at DATETIME(6) NOT NULL,
    last_source_ip VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_disk_monitor_host_host_id (host_id),
    KEY idx_disk_monitor_host_last_seen (last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE disk_monitor_sample (
    id BIGINT NOT NULL AUTO_INCREMENT,
    host_id VARCHAR(128) NOT NULL,
    device VARCHAR(128) NOT NULL,
    sampled_at DATETIME(6) NOT NULL,
    detail_json JSON NOT NULL,
    raw_smart_output LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_disk_monitor_sample_host_device_time (host_id, device, sampled_at),
    KEY idx_disk_monitor_sample_time (sampled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
