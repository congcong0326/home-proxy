ALTER TABLE wol_configs ADD COLUMN worker_id VARCHAR(128) NOT NULL DEFAULT 'default';
ALTER TABLE wol_configs ADD INDEX idx_wol_configs_worker_id (worker_id);
