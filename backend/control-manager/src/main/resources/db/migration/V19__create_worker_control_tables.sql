CREATE TABLE IF NOT EXISTS worker_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_type VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    created_at DATETIME NOT NULL,
    consumed_at DATETIME NULL,
    result_success TINYINT(1) NULL,
    result_message VARCHAR(1024) NULL,
    result_reported_at DATETIME NULL,
    INDEX idx_worker_tasks_consumed_created (consumed_at, created_at)
);

CREATE TABLE IF NOT EXISTS worker_status (
    worker_id VARCHAR(128) PRIMARY KEY,
    hostname VARCHAR(255) NULL,
    started_at DATETIME NULL,
    last_seen_at DATETIME NOT NULL,
    last_config_hash VARCHAR(128) NULL,
    uptime_seconds BIGINT NULL,
    heap_used_bytes BIGINT NULL,
    heap_max_bytes BIGINT NULL,
    running_inbound_count INT NULL,
    active_connection_count INT NULL
);
