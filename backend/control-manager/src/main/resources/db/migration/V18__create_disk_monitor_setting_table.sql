CREATE TABLE disk_monitor_setting (
    setting_key VARCHAR(64) NOT NULL,
    setting_value VARCHAR(512) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
