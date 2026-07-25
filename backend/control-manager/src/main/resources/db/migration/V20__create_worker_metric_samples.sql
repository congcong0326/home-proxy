CREATE TABLE IF NOT EXISTS worker_metric_samples (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    worker_id VARCHAR(128) NOT NULL,
    sampled_at DATETIME NOT NULL,
    started_at DATETIME NULL,
    last_config_hash VARCHAR(128) NULL,
    heap_used_bytes BIGINT NULL,
    heap_max_bytes BIGINT NULL,
    running_inbound_count INT NULL,
    active_connection_count INT NULL,
    INDEX idx_worker_metric_samples_worker_time (worker_id, sampled_at),
    INDEX idx_worker_metric_samples_time (sampled_at)
);
