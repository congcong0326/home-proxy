CREATE TABLE IF NOT EXISTS users (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  credential VARCHAR(255) NULL,
  status TINYINT NOT NULL DEFAULT 1,
  remark VARCHAR(255) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_users_username (username),
  INDEX idx_users_status (status)
);

CREATE TABLE IF NOT EXISTS inbound_configs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(64) NOT NULL,
  protocol VARCHAR(32) NOT NULL,
  listen_ip VARCHAR(64) NOT NULL,
  port INT NOT NULL,
  tls_enabled TINYINT(1) NOT NULL DEFAULT 0,
  sniff_enabled TINYINT(1) NOT NULL DEFAULT 0,
  ss_method VARCHAR(64) NULL,
  allowed_user_ids JSON NULL,
  route_ids JSON NULL,
  status TINYINT NOT NULL DEFAULT 1,
  notes VARCHAR(255) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_inbound_configs_port (listen_ip, port),
  INDEX idx_inbound_configs_status (status),
  INDEX idx_inbound_configs_protocol (protocol)
);

CREATE TABLE IF NOT EXISTS routes (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(64) NOT NULL,
  rules_json JSON NOT NULL,
  policy VARCHAR(32) NOT NULL,
  outbound_tag VARCHAR(64) NULL,
  outbound_proxy_type VARCHAR(16) NULL,
  outbound_proxy_host VARCHAR(255) NULL,
  outbound_proxy_port INT NULL,
  outbound_proxy_username VARCHAR(64) NULL,
  outbound_proxy_password VARCHAR(255) NULL,
  status TINYINT NOT NULL DEFAULT 1,
  notes VARCHAR(255) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  INDEX idx_routes_policy (policy),
  INDEX idx_routes_status (status)
);

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
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  INDEX idx_rate_limits_scope (scope_type),
  INDEX idx_rate_limits_enabled (enabled),
  INDEX idx_rate_limits_effective (effective_from, effective_to)
);