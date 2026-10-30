-- SQLite baseline schema for the control-manager metadata database.
--
-- Conventions (see docs/development/controller-sqlite-migration-design.md):
--   * timestamps are TEXT, UTC, 'yyyy-MM-dd HH:mm:ss.SSSSSS' (lexicographic order == time order)
--   * booleans are INTEGER 0/1, JSON documents are TEXT, token hashes are BLOB
--   * identifier-like columns that were case-insensitive under MySQL collations use COLLATE NOCASE
--   * AUTOINCREMENT keeps primary keys from being reused after deletes

CREATE TABLE admin_user (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    username             TEXT    NOT NULL COLLATE NOCASE,
    password_hash        TEXT    NOT NULL,
    roles                TEXT    NOT NULL,
    must_change_password INTEGER NOT NULL DEFAULT 1,
    ver                  INTEGER NOT NULL DEFAULT 1,
    status               INTEGER NOT NULL DEFAULT 1,
    created_at           TEXT    NOT NULL,
    updated_at           TEXT    NOT NULL,
    CONSTRAINT uk_admin_user_username UNIQUE (username)
);

CREATE TABLE admin_login_history (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id    INTEGER,
    username   TEXT    NOT NULL COLLATE NOCASE,
    success    INTEGER NOT NULL,
    ip         TEXT,
    ua         TEXT,
    reason     TEXT,
    created_at TEXT    NOT NULL
);
CREATE INDEX idx_admin_login_user ON admin_login_history (user_id, created_at);
CREATE INDEX idx_admin_login_name ON admin_login_history (username, created_at);

CREATE TABLE admin_token_blacklist (
    jti        TEXT PRIMARY KEY,
    expires_at TEXT NOT NULL
);
CREATE INDEX idx_admin_token_exp ON admin_token_blacklist (expires_at);

CREATE TABLE users (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    username   TEXT    NOT NULL COLLATE NOCASE,
    credential TEXT,
    ip_address TEXT    COLLATE NOCASE,
    status     INTEGER NOT NULL DEFAULT 1,
    remark     TEXT,
    created_at TEXT    NOT NULL,
    updated_at TEXT    NOT NULL,
    CONSTRAINT uk_users_username UNIQUE (username),
    CONSTRAINT uk_users_ip_address UNIQUE (ip_address)
);
CREATE INDEX idx_users_status ON users (status);

CREATE TABLE routes (
    id                        INTEGER PRIMARY KEY AUTOINCREMENT,
    name                      TEXT    NOT NULL COLLATE NOCASE,
    rules_json                TEXT    NOT NULL,
    policy                    TEXT    NOT NULL,
    outbound_tag              TEXT,
    outbound_proxy_type       TEXT,
    outbound_transport        TEXT    NOT NULL DEFAULT 'TCP',
    outbound_tls_config_json  TEXT,
    outbound_proxy_host       TEXT,
    outbound_proxy_port       INTEGER,
    outbound_proxy_username   TEXT,
    outbound_proxy_password   TEXT,
    outbound_proxy_enc_algo   TEXT,
    outbound_proxy_config_json TEXT,
    status                    INTEGER NOT NULL DEFAULT 1,
    notes                     TEXT,
    created_at                TEXT    NOT NULL,
    updated_at                TEXT    NOT NULL,
    CONSTRAINT uk_routes_name UNIQUE (name)
);
CREATE INDEX idx_routes_policy ON routes (policy);
CREATE INDEX idx_routes_status ON routes (status);

CREATE TABLE rule_set (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    rule_key      TEXT    NOT NULL COLLATE NOCASE,
    name          TEXT    NOT NULL,
    category      TEXT    NOT NULL,
    match_target  TEXT    NOT NULL,
    source_type   TEXT    NOT NULL,
    source_config TEXT,
    enabled       INTEGER NOT NULL DEFAULT 1,
    published     INTEGER NOT NULL DEFAULT 0,
    version_no    INTEGER NOT NULL DEFAULT 1,
    description   TEXT,
    item_count    INTEGER NOT NULL DEFAULT 0,
    created_at    TEXT    NOT NULL,
    updated_at    TEXT    NOT NULL,
    CONSTRAINT uk_rule_set_rule_key UNIQUE (rule_key)
);
CREATE INDEX idx_rule_set_enabled_published ON rule_set (enabled, published);
CREATE INDEX idx_rule_set_category ON rule_set (category);
CREATE INDEX idx_rule_set_updated_at ON rule_set (updated_at);
CREATE INDEX idx_rule_set_category_updated_at ON rule_set (category, updated_at);
CREATE INDEX idx_rule_set_enabled_published_updated_at ON rule_set (enabled, published, updated_at);
CREATE INDEX idx_rule_set_updated_at_id ON rule_set (updated_at, id);
CREATE INDEX idx_rule_set_enabled_published_updated_at_id ON rule_set (enabled, published, updated_at, id);
CREATE INDEX idx_rule_set_category_enabled_published_updated_at_id ON rule_set (category, enabled, published, updated_at, id);

CREATE TABLE rule_set_payload (
    rule_set_id INTEGER PRIMARY KEY,
    items_json  TEXT NOT NULL,
    CONSTRAINT fk_rule_set_payload_rule_set FOREIGN KEY (rule_set_id) REFERENCES rule_set (id) ON DELETE CASCADE
);

CREATE TABLE proxy_worker (
    worker_id             TEXT    PRIMARY KEY COLLATE NOCASE,
    display_name          TEXT    NOT NULL,
    enabled               INTEGER NOT NULL DEFAULT 1,
    description           TEXT,
    management_url        TEXT,
    management_token      TEXT,
    token_hash            BLOB,
    created_at            TEXT    NOT NULL,
    updated_at            TEXT    NOT NULL,
    log_reporting_enabled INTEGER NOT NULL DEFAULT 1,
    log_level             TEXT    NOT NULL DEFAULT 'INFO',
    CONSTRAINT uk_proxy_worker_token_hash UNIQUE (token_hash)
);

CREATE TABLE inbound_configs (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    worker_id              TEXT    NOT NULL DEFAULT 'default' COLLATE NOCASE,
    name                   TEXT    NOT NULL,
    protocol               TEXT    NOT NULL,
    listen_ip              TEXT    NOT NULL,
    port                   INTEGER NOT NULL,
    tls_enabled            INTEGER NOT NULL DEFAULT 0,
    sniff_enabled          INTEGER NOT NULL DEFAULT 0,
    fake_ip_enabled        INTEGER NOT NULL DEFAULT 0,
    ss_method              TEXT,
    inbound_route_bindings TEXT    NOT NULL,
    fallback_route_id      INTEGER,
    anonymous_user_id      INTEGER,
    status                 INTEGER NOT NULL DEFAULT 1,
    notes                  TEXT,
    created_at             TEXT    NOT NULL,
    updated_at             TEXT    NOT NULL,
    CONSTRAINT uk_inbound_configs_worker_port UNIQUE (worker_id, listen_ip, port),
    CONSTRAINT fk_inbound_configs_worker FOREIGN KEY (worker_id) REFERENCES proxy_worker (worker_id) ON DELETE RESTRICT,
    CONSTRAINT fk_inbound_configs_fallback_route FOREIGN KEY (fallback_route_id) REFERENCES routes (id) ON DELETE RESTRICT,
    CONSTRAINT fk_inbound_configs_anonymous_user FOREIGN KEY (anonymous_user_id) REFERENCES users (id) ON DELETE RESTRICT
);
CREATE INDEX idx_inbound_configs_status ON inbound_configs (status);
CREATE INDEX idx_inbound_configs_protocol ON inbound_configs (protocol);
CREATE INDEX idx_inbound_configs_worker_status ON inbound_configs (worker_id, status);

CREATE TABLE worker_status (
    worker_id               TEXT PRIMARY KEY COLLATE NOCASE,
    hostname                TEXT,
    started_at              TEXT,
    last_seen_at            TEXT NOT NULL,
    last_config_hash        TEXT,
    applied_config_hash     TEXT,
    apply_success           INTEGER,
    apply_error             TEXT,
    applied_at              TEXT,
    capabilities_json       TEXT NOT NULL,
    uptime_seconds          INTEGER,
    heap_used_bytes         INTEGER,
    heap_max_bytes          INTEGER,
    running_inbound_count   INTEGER,
    active_connection_count INTEGER,
    metrics_json            TEXT,
    CONSTRAINT fk_worker_status_worker FOREIGN KEY (worker_id) REFERENCES proxy_worker (worker_id) ON DELETE RESTRICT
);

CREATE TABLE worker_tasks (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    worker_id          TEXT    NOT NULL DEFAULT 'default' COLLATE NOCASE,
    task_type          TEXT    NOT NULL,
    payload_json       TEXT    NOT NULL,
    created_at         TEXT    NOT NULL,
    consumed_at        TEXT,
    result_success     INTEGER,
    result_message     TEXT,
    result_reported_at TEXT,
    CONSTRAINT fk_worker_tasks_worker FOREIGN KEY (worker_id) REFERENCES proxy_worker (worker_id) ON DELETE RESTRICT
);
CREATE INDEX idx_worker_tasks_consumed_created ON worker_tasks (consumed_at, created_at);
CREATE INDEX idx_worker_tasks_worker_consumed_created ON worker_tasks (worker_id, consumed_at, created_at);

CREATE TABLE wol_configs (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT    NOT NULL COLLATE NOCASE,
    ip_address  TEXT    NOT NULL COLLATE NOCASE,
    subnet_mask TEXT    NOT NULL DEFAULT '255.255.255.255',
    mac_address TEXT    NOT NULL COLLATE NOCASE,
    wol_port    INTEGER NOT NULL DEFAULT 9,
    status      INTEGER NOT NULL DEFAULT 1,
    notes       TEXT,
    created_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f000', 'now')),
    updated_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f000', 'now')),
    worker_id   TEXT    NOT NULL DEFAULT 'default' COLLATE NOCASE,
    CONSTRAINT uk_wol_configs_name UNIQUE (name),
    CONSTRAINT uk_wol_configs_ip UNIQUE (ip_address),
    CONSTRAINT uk_wol_configs_mac UNIQUE (mac_address)
);
CREATE INDEX idx_wol_configs_status ON wol_configs (status);
CREATE INDEX idx_wol_configs_worker_id ON wol_configs (worker_id);

CREATE TABLE disk_monitor_host (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    host_id        TEXT NOT NULL COLLATE NOCASE,
    host_name      TEXT,
    last_seen_at   TEXT NOT NULL,
    last_source_ip TEXT,
    created_at     TEXT NOT NULL,
    updated_at     TEXT NOT NULL,
    CONSTRAINT uk_disk_monitor_host_host_id UNIQUE (host_id)
);
CREATE INDEX idx_disk_monitor_host_last_seen ON disk_monitor_host (last_seen_at);

CREATE TABLE disk_monitor_sample (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    host_id          TEXT NOT NULL COLLATE NOCASE,
    device           TEXT NOT NULL COLLATE NOCASE,
    sampled_at       TEXT NOT NULL,
    detail_json      TEXT NOT NULL,
    raw_smart_output TEXT NOT NULL,
    created_at       TEXT NOT NULL
);
CREATE INDEX idx_disk_monitor_sample_host_device_time ON disk_monitor_sample (host_id, device, sampled_at);
CREATE INDEX idx_disk_monitor_sample_time ON disk_monitor_sample (sampled_at);

CREATE TABLE disk_monitor_setting (
    setting_key   TEXT PRIMARY KEY COLLATE NOCASE,
    setting_value TEXT NOT NULL,
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL
);

CREATE TABLE mail_gateway (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    name             TEXT    NOT NULL,
    host             TEXT    NOT NULL,
    port             INTEGER NOT NULL,
    username         TEXT    NOT NULL,
    password         TEXT    NOT NULL,
    protocol         TEXT    NOT NULL DEFAULT 'smtp',
    ssl_enabled      INTEGER NOT NULL DEFAULT 0,
    starttls_enabled INTEGER NOT NULL DEFAULT 0,
    from_address     TEXT,
    enabled          INTEGER NOT NULL DEFAULT 1,
    created_at       TEXT    NOT NULL,
    updated_at       TEXT    NOT NULL
);

CREATE TABLE mail_target (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    biz_key    TEXT    NOT NULL COLLATE NOCASE,
    to_list    TEXT    NOT NULL,
    cc_list    TEXT,
    bcc_list   TEXT,
    gateway_id INTEGER,
    enabled    INTEGER NOT NULL DEFAULT 1,
    created_at TEXT    NOT NULL,
    updated_at TEXT    NOT NULL,
    CONSTRAINT uk_mail_target_biz_key UNIQUE (biz_key),
    CONSTRAINT fk_mail_target_gateway FOREIGN KEY (gateway_id) REFERENCES mail_gateway (id) ON DELETE SET NULL
);

CREATE TABLE mail_send_log (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    biz_key       TEXT    NOT NULL COLLATE NOCASE,
    gateway_id    INTEGER,
    to_list       TEXT,
    cc_list       TEXT,
    bcc_list      TEXT,
    subject       TEXT,
    content_type  TEXT,
    content_size  INTEGER,
    status        TEXT    NOT NULL,
    error_message TEXT,
    request_id    TEXT,
    created_at    TEXT    NOT NULL,
    CONSTRAINT fk_mail_send_gateway FOREIGN KEY (gateway_id) REFERENCES mail_gateway (id) ON DELETE SET NULL
);
CREATE INDEX idx_send_log_biz_key_created ON mail_send_log (biz_key, created_at);

CREATE TABLE scheduled_task (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    task_key         TEXT    NOT NULL COLLATE NOCASE,
    task_type        TEXT    NOT NULL,
    biz_key          TEXT    COLLATE NOCASE,
    cron_expression  TEXT    NOT NULL,
    config_json      TEXT,
    description      TEXT,
    enabled          INTEGER NOT NULL DEFAULT 1,
    last_executed_at TEXT,
    created_at       TEXT    NOT NULL,
    updated_at       TEXT    NOT NULL,
    CONSTRAINT uk_scheduled_task_key UNIQUE (task_key)
);
