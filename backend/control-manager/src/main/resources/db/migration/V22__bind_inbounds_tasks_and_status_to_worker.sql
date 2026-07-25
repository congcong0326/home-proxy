ALTER TABLE worker_tasks
    ADD COLUMN worker_id VARCHAR(128)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL AFTER id;

UPDATE worker_tasks SET worker_id = 'default' WHERE worker_id IS NULL;

ALTER TABLE worker_tasks
    MODIFY COLUMN worker_id VARCHAR(128)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'default',
    ADD INDEX idx_worker_tasks_worker_consumed_created (worker_id, consumed_at, created_at),
    ADD CONSTRAINT fk_worker_tasks_worker FOREIGN KEY (worker_id) REFERENCES proxy_worker(worker_id) ON DELETE RESTRICT;

ALTER TABLE worker_status
    MODIFY COLUMN worker_id VARCHAR(128)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    ADD COLUMN applied_config_hash VARCHAR(128) NULL AFTER last_config_hash,
    ADD COLUMN apply_success TINYINT(1) NULL AFTER applied_config_hash,
    ADD COLUMN apply_error VARCHAR(1024) NULL AFTER apply_success,
    ADD COLUMN applied_at DATETIME(6) NULL AFTER apply_error,
    ADD CONSTRAINT fk_worker_status_worker FOREIGN KEY (worker_id) REFERENCES proxy_worker(worker_id) ON DELETE RESTRICT;
