CREATE TABLE IF NOT EXISTS admin_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    roles VARCHAR(255) NOT NULL,
    must_change_password TINYINT(1) NOT NULL DEFAULT 1,
    ver INT NOT NULL DEFAULT 1,
    status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_admin_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS admin_login_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NULL,
    username VARCHAR(64) NOT NULL,
    success TINYINT(1) NOT NULL,
    ip VARCHAR(64) NULL,
    ua VARCHAR(255) NULL,
    reason VARCHAR(128) NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_admin_login_user (user_id, created_at),
    INDEX idx_admin_login_name (username, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS admin_token_blacklist (
    jti VARCHAR(64) PRIMARY KEY,
    expires_at DATETIME NOT NULL,
    INDEX idx_admin_token_exp (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    credential VARCHAR(255) NULL,
    ip_address VARCHAR(45) NULL,
    status TINYINT NOT NULL DEFAULT 1,
    remark VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_users_username (username),
    UNIQUE KEY uk_users_ip_address (ip_address),
    INDEX idx_users_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS inbound_configs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    protocol VARCHAR(32) NOT NULL,
    listen_ip VARCHAR(64) NOT NULL,
    port INT NOT NULL,
    tls_enabled TINYINT(1) NOT NULL DEFAULT 0,
    sniff_enabled TINYINT(1) NOT NULL DEFAULT 0,
    ss_method VARCHAR(64) NULL,
    inbound_route_bindings JSON NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    notes VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_inbound_configs_port (listen_ip, port),
    INDEX idx_inbound_configs_status (status),
    INDEX idx_inbound_configs_protocol (protocol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS routes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    rules_json JSON NOT NULL,
    policy VARCHAR(32) NOT NULL,
    outbound_tag VARCHAR(64) NULL,
    outbound_proxy_type VARCHAR(32) NULL,
    outbound_proxy_host VARCHAR(255) NULL,
    outbound_proxy_port INT NULL,
    outbound_proxy_username VARCHAR(64) NULL,
    outbound_proxy_password VARCHAR(255) NULL,
    outbound_proxy_enc_algo VARCHAR(64) NULL,
    outbound_proxy_config_json JSON NULL,
    status TINYINT NOT NULL DEFAULT 1,
    notes VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_routes_name (name),
    INDEX idx_routes_policy (policy),
    INDEX idx_routes_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rate_limits (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    scope_type VARCHAR(16) NOT NULL,
    user_ids JSON NULL,
    uplink_limit_bps BIGINT NULL,
    downlink_limit_bps BIGINT NULL,
    burst_bytes BIGINT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    effective_time_start TIME NULL,
    effective_time_end TIME NULL,
    effective_from DATE NULL,
    effective_to DATE NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_rate_limits_scope (scope_type),
    INDEX idx_rate_limits_enabled (enabled),
    INDEX idx_rate_limits_effective (effective_from, effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agg_month_user_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    month_date DATE NOT NULL,
    user_id BIGINT NOT NULL,
    username VARCHAR(64) NULL,
    requests_count BIGINT NOT NULL DEFAULT 0,
    bytes_in BIGINT NOT NULL DEFAULT 0,
    bytes_out BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_user_month (month_date, user_id),
    INDEX idx_user_month (month_date, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agg_month_app_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    month_date DATE NOT NULL,
    target_host VARCHAR(255) NOT NULL,
    requests_count BIGINT NOT NULL DEFAULT 0,
    bytes_in BIGINT NOT NULL DEFAULT 0,
    bytes_out BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_app_month (month_date, target_host),
    INDEX idx_app_month (month_date, target_host)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agg_month_user_app_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    month_date DATE NOT NULL,
    user_id BIGINT NOT NULL,
    username VARCHAR(64) NULL,
    target_host VARCHAR(255) NOT NULL,
    requests_count BIGINT NOT NULL DEFAULT 0,
    bytes_in BIGINT NOT NULL DEFAULT 0,
    bytes_out BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_user_app_month (month_date, user_id, target_host),
    INDEX idx_user_app_month (month_date, user_id, target_host)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agg_month_src_geo_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    month_date DATE NOT NULL,
    src_geo_country VARCHAR(64) NULL,
    src_geo_city VARCHAR(64) NULL,
    requests_count BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_src_geo_month (month_date, src_geo_country, src_geo_city),
    INDEX idx_src_geo_month (month_date, src_geo_country, src_geo_city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agg_month_dst_geo_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    month_date DATE NOT NULL,
    dst_geo_country VARCHAR(64) NULL,
    dst_geo_city VARCHAR(64) NULL,
    requests_count BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_dst_geo_month (month_date, dst_geo_country, dst_geo_city),
    INDEX idx_dst_geo_month (month_date, dst_geo_country, dst_geo_city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agg_day_user_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    day_date DATE NOT NULL,
    user_id BIGINT NOT NULL,
    username VARCHAR(64) NULL,
    requests_count BIGINT NOT NULL DEFAULT 0,
    bytes_in BIGINT NOT NULL DEFAULT 0,
    bytes_out BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_user_day (day_date, user_id),
    INDEX idx_user_day (day_date, user_id, username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agg_day_app_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    day_date DATE NOT NULL,
    target_host VARCHAR(255) NOT NULL,
    requests_count BIGINT NOT NULL DEFAULT 0,
    bytes_in BIGINT NOT NULL DEFAULT 0,
    bytes_out BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_app_day (day_date, target_host),
    INDEX idx_app_day (day_date, target_host)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agg_day_user_app_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    day_date DATE NOT NULL,
    user_id BIGINT NOT NULL,
    username VARCHAR(64) NULL,
    target_host VARCHAR(255) NOT NULL,
    requests_count BIGINT NOT NULL DEFAULT 0,
    bytes_in BIGINT NOT NULL DEFAULT 0,
    bytes_out BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_user_app_day (day_date, user_id, target_host),
    INDEX idx_user_app_day (day_date, user_id, target_host)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agg_day_src_geo_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    day_date DATE NOT NULL,
    src_geo_country VARCHAR(64) NULL,
    src_geo_city VARCHAR(64) NULL,
    requests_count BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_src_geo_day (day_date, src_geo_country, src_geo_city),
    INDEX idx_src_geo_day (day_date, src_geo_country, src_geo_city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agg_day_dst_geo_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    day_date DATE NOT NULL,
    dst_geo_country VARCHAR(64) NULL,
    dst_geo_city VARCHAR(64) NULL,
    requests_count BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_dst_geo_day (day_date, dst_geo_country, dst_geo_city),
    INDEX idx_dst_geo_day (day_date, dst_geo_country, dst_geo_city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agg_minute_traffic_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    minute_time DATETIME(6) NOT NULL,
    user_id BIGINT NULL,
    byte_in BIGINT NOT NULL DEFAULT 0,
    byte_out BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_minute_traffic_time (minute_time),
    INDEX idx_minute_traffic_user_time (user_id, minute_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS wol_configs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    ip_address VARCHAR(64) NOT NULL,
    subnet_mask VARCHAR(64) NOT NULL DEFAULT '255.255.255.255',
    mac_address VARCHAR(64) NOT NULL,
    wol_port INT NOT NULL DEFAULT 9,
    status TINYINT NOT NULL DEFAULT 1,
    notes VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_wol_configs_name (name),
    UNIQUE KEY uk_wol_configs_ip (ip_address),
    UNIQUE KEY uk_wol_configs_mac (mac_address),
    INDEX idx_wol_configs_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dns_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    domain_pattern VARCHAR(255) NOT NULL,
    match_type VARCHAR(20) NOT NULL,
    record_type VARCHAR(10) NOT NULL,
    answer_ipv4 VARCHAR(64) NULL,
    answer_ipv6 VARCHAR(128) NULL,
    ttl_seconds INT NOT NULL,
    action VARCHAR(20) NOT NULL,
    priority INT NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    remark VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_dns_rule_domain (domain_pattern, record_type, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mail_gateway (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INT NOT NULL,
    username VARCHAR(128) NOT NULL,
    password VARCHAR(255) NOT NULL,
    protocol VARCHAR(16) NOT NULL DEFAULT 'smtp',
    ssl_enabled TINYINT(1) NOT NULL DEFAULT 0,
    starttls_enabled TINYINT(1) NOT NULL DEFAULT 0,
    from_address VARCHAR(255),
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mail_target (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    biz_key VARCHAR(128) NOT NULL,
    to_list VARCHAR(1024) NOT NULL,
    cc_list VARCHAR(1024),
    bcc_list VARCHAR(1024),
    gateway_id BIGINT,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_mail_target_biz_key UNIQUE (biz_key),
    CONSTRAINT fk_mail_target_gateway FOREIGN KEY (gateway_id) REFERENCES mail_gateway(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mail_send_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    biz_key VARCHAR(128) NOT NULL,
    gateway_id BIGINT,
    to_list VARCHAR(1024),
    cc_list VARCHAR(1024),
    bcc_list VARCHAR(1024),
    subject VARCHAR(255),
    content_type VARCHAR(64),
    content_size INT,
    status VARCHAR(16) NOT NULL,
    error_message VARCHAR(1024),
    request_id VARCHAR(128),
    created_at DATETIME(6) NOT NULL,
    INDEX idx_send_log_biz_key_created (biz_key, created_at),
    CONSTRAINT fk_mail_send_gateway FOREIGN KEY (gateway_id) REFERENCES mail_gateway(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
