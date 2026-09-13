ALTER TABLE worker_status
    ADD COLUMN capabilities_json JSON NULL AFTER applied_at;

UPDATE worker_status
SET capabilities_json = JSON_ARRAY()
WHERE capabilities_json IS NULL;

ALTER TABLE worker_status
    MODIFY COLUMN capabilities_json JSON NOT NULL;
