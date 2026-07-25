CREATE TABLE proxy_worker (
    worker_id VARCHAR(128) PRIMARY KEY,
    display_name VARCHAR(128) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    description VARCHAR(255) NULL,
    token_hash BINARY(32) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_proxy_worker_token_hash (token_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO proxy_worker (worker_id, display_name, enabled, created_at, updated_at)
SELECT DISTINCT worker_id, worker_id, 1, NOW(6), NOW(6)
FROM worker_status
WHERE worker_id IS NOT NULL AND worker_id <> ''
ON DUPLICATE KEY UPDATE worker_id = VALUES(worker_id);

INSERT INTO proxy_worker (worker_id, display_name, enabled, created_at, updated_at)
VALUES ('default', 'default', 1, NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE worker_id = VALUES(worker_id);

ALTER TABLE inbound_configs
    ADD COLUMN worker_id VARCHAR(128) NULL AFTER id,
    ADD COLUMN fallback_route_id BIGINT NULL AFTER inbound_route_bindings,
    ADD COLUMN anonymous_user_id BIGINT NULL AFTER fallback_route_id;

UPDATE inbound_configs SET worker_id = 'default' WHERE worker_id IS NULL;

ALTER TABLE inbound_configs
    MODIFY COLUMN worker_id VARCHAR(128) NOT NULL DEFAULT 'default',
    DROP INDEX uk_inbound_configs_port,
    ADD UNIQUE KEY uk_inbound_configs_worker_port (worker_id, listen_ip, port),
    ADD INDEX idx_inbound_configs_worker_status (worker_id, status),
    ADD CONSTRAINT fk_inbound_configs_worker FOREIGN KEY (worker_id) REFERENCES proxy_worker(worker_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_inbound_configs_fallback_route FOREIGN KEY (fallback_route_id) REFERENCES routes(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_inbound_configs_anonymous_user FOREIGN KEY (anonymous_user_id) REFERENCES users(id) ON DELETE RESTRICT;
