-- WOL配置表
CREATE TABLE IF NOT EXISTS wol_configs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(64) NOT NULL COMMENT '设备名称',
  ip_address VARCHAR(64) NOT NULL COMMENT 'IP地址',
  subnet_mask VARCHAR(64) NOT NULL DEFAULT '255.255.255.255' COMMENT '子网掩码',
  mac_address VARCHAR(64) NOT NULL COMMENT 'MAC地址',
  wol_port INT NOT NULL DEFAULT 9 COMMENT 'WOL端口',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1-启用，0-禁用',
  notes VARCHAR(255) NULL COMMENT '备注',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY uk_wol_configs_name (name),
  UNIQUE KEY uk_wol_configs_ip (ip_address),
  UNIQUE KEY uk_wol_configs_mac (mac_address),
  INDEX idx_wol_configs_status (status)
) COMMENT='WOL配置表';