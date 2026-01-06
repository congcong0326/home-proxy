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
