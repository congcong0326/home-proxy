CREATE TABLE IF NOT EXISTS scheduled_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_key VARCHAR(128) NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    biz_key VARCHAR(128),
    cron_expression VARCHAR(128) NOT NULL,
    config_json TEXT,
    description VARCHAR(255),
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    last_executed_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_scheduled_task_key UNIQUE (task_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
