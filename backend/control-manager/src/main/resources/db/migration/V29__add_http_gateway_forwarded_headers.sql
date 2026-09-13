ALTER TABLE http_gateways
    ADD COLUMN client_ip_source VARCHAR(32) NOT NULL DEFAULT 'DIRECT' AFTER default_certificate_id;

ALTER TABLE http_locations
    ADD COLUMN forwarded_header_mode VARCHAR(32) NOT NULL DEFAULT 'X_FORWARDED' AFTER upstream_host,
    ADD COLUMN request_header_rules JSON NULL AFTER forwarded_header_mode;

UPDATE http_locations
SET request_header_rules = JSON_ARRAY()
WHERE request_header_rules IS NULL;

ALTER TABLE http_locations
    MODIFY COLUMN request_header_rules JSON NOT NULL;
