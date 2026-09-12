ALTER TABLE proxy_worker
    ADD COLUMN log_reporting_enabled BOOLEAN NOT NULL DEFAULT TRUE;
