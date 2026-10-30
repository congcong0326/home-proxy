-- 日志类型与字段精简：ClickHouse 清理脚本（手工执行，不随启动流程自动运行）。
--
-- 执行前提：
--   1. 所有 Worker 已升级到精简版并稳定运行；旧 Worker 仍会写入被删除的列。
--   2. 控制端已升级到精简版。执行本脚本后不能再回滚到旧版控制端（旧代码会向已删除的列写入）。
-- 以下操作不可恢复，请先确认并备份。

-- 1. 诊断事件表整表删除
DROP TABLE IF EXISTS telemetry.proxy_event_log_v1;

-- 2. 连接日志专属字段
ALTER TABLE telemetry.proxy_session_log_v1
  DROP COLUMN IF EXISTS error_message,
  DROP COLUMN IF EXISTS close_initiator,
  DROP COLUMN IF EXISTS close_reason,
  DROP COLUMN IF EXISTS outbound_mode,
  DROP COLUMN IF EXISTS channel_ready_basis,
  DROP COLUMN IF EXISTS target_confirmation_level,
  DROP COLUMN IF EXISTS proxy_handshake_outcome,
  DROP COLUMN IF EXISTS traffic_classification_version,
  DROP COLUMN IF EXISTS traffic_category_basis,
  DROP COLUMN IF EXISTS target_address_class,
  DROP COLUMN IF EXISTS target_network_scope,
  DROP COLUMN IF EXISTS client_network_scope,
  DROP COLUMN IF EXISTS outbound_peer_network_scope,
  DROP COLUMN IF EXISTS route_selection_duration_us,
  DROP COLUMN IF EXISTS upload_backpressure_duration_us,
  DROP COLUMN IF EXISTS download_backpressure_duration_us,
  DROP COLUMN IF EXISTS first_upload_at,
  DROP COLUMN IF EXISTS first_download_at,
  DROP COLUMN IF EXISTS route_selection;

-- 3. 认证日志专属字段
ALTER TABLE telemetry.credential_auth_log_v1
  DROP COLUMN IF EXISTS verified_username;

-- 4. 公共字段：连接、DNS、认证三张表各执行一次
ALTER TABLE telemetry.proxy_session_log_v1
  DROP COLUMN IF EXISTS client_ip_source,
  DROP COLUMN IF EXISTS transport_peer_ip,
  DROP COLUMN IF EXISTS transport_peer_port,
  DROP COLUMN IF EXISTS config_version,
  DROP COLUMN IF EXISTS rule_set_version;

ALTER TABLE telemetry.dns_query_log_v1
  DROP COLUMN IF EXISTS client_ip_source,
  DROP COLUMN IF EXISTS transport_peer_ip,
  DROP COLUMN IF EXISTS transport_peer_port,
  DROP COLUMN IF EXISTS config_version,
  DROP COLUMN IF EXISTS rule_set_version;

ALTER TABLE telemetry.credential_auth_log_v1
  DROP COLUMN IF EXISTS client_ip_source,
  DROP COLUMN IF EXISTS transport_peer_ip,
  DROP COLUMN IF EXISTS transport_peer_port,
  DROP COLUMN IF EXISTS config_version,
  DROP COLUMN IF EXISTS rule_set_version;

-- 可选（异步 mutation，建议低峰期执行）：清理历史非失败认证记录以释放空间。
-- 所有认证统计查询已按失败类过滤，不执行也不影响结果。
-- ALTER TABLE telemetry.credential_auth_log_v1 DELETE WHERE auth_outcome IN ('SUCCESS','CHALLENGE','CANCELLED');
