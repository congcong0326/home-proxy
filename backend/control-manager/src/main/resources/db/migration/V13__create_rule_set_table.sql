CREATE TABLE IF NOT EXISTS rule_set (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_key VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  category VARCHAR(32) NOT NULL,
  match_target VARCHAR(32) NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  source_config TEXT NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  published TINYINT(1) NOT NULL DEFAULT 0,
  version_no BIGINT NOT NULL DEFAULT 1,
  description VARCHAR(255) NULL,
  items_json JSON NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_rule_set_rule_key (rule_key),
  INDEX idx_rule_set_enabled_published (enabled, published),
  INDEX idx_rule_set_category (category)
)ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
