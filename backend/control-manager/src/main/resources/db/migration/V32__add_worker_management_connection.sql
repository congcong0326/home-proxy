ALTER TABLE proxy_worker
    ADD COLUMN management_url VARCHAR(512) NULL AFTER description,
    ADD COLUMN management_token VARCHAR(128) NULL AFTER management_url;
