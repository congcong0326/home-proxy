ALTER TABLE http_gateways
    DROP FOREIGN KEY fk_http_gateways_fallback_route,
    DROP COLUMN egress_route_ids,
    DROP COLUMN fallback_route_id,
    DROP COLUMN upstream_max_idle_connections_per_event_loop,
    ADD COLUMN max_active_sessions INT NOT NULL DEFAULT 4096 AFTER idle_timeout_ms;

-- Old Virtual Hosts only selected certificate/domain; their Location rows held the action and upstream.
-- The new model must not guess one binding from possibly conflicting Location rows.
DELETE FROM http_virtual_hosts;

ALTER TABLE http_virtual_hosts
    ADD COLUMN action VARCHAR(32) NOT NULL AFTER server_certificate_id,
    ADD COLUMN target_host VARCHAR(255) NULL AFTER action,
    ADD COLUMN target_port INT NULL AFTER target_host,
    ADD COLUMN egress_route_id BIGINT NULL AFTER target_port,
    ADD COLUMN preserve_host TINYINT NOT NULL DEFAULT 1 AFTER egress_route_id,
    ADD COLUMN upstream_host VARCHAR(255) NULL AFTER preserve_host,
    ADD COLUMN forwarded_header_mode VARCHAR(32) NOT NULL DEFAULT 'X_FORWARDED' AFTER upstream_host,
    ADD COLUMN request_header_rules JSON NULL AFTER forwarded_header_mode,
    ADD COLUMN redirect_location VARCHAR(2048) NULL AFTER request_header_rules,
    ADD COLUMN redirect_status INT NULL AFTER redirect_location,
    ADD COLUMN reject_status INT NULL AFTER redirect_status,
    ADD COLUMN reject_body VARCHAR(8192) NULL AFTER reject_status,
    ADD COLUMN max_active_sse_sessions INT NOT NULL DEFAULT 128 AFTER reject_body,
    ADD KEY idx_http_virtual_hosts_egress_route (egress_route_id),
    ADD CONSTRAINT fk_http_virtual_hosts_egress_route
        FOREIGN KEY (egress_route_id) REFERENCES routes(id) ON DELETE RESTRICT;

UPDATE http_virtual_hosts
SET request_header_rules = JSON_ARRAY()
WHERE request_header_rules IS NULL;

ALTER TABLE http_virtual_hosts
    MODIFY COLUMN request_header_rules JSON NOT NULL;

ALTER TABLE http_locations
    DROP FOREIGN KEY fk_http_locations_static_site;

DROP TABLE http_locations;

ALTER TABLE static_sites
    DROP FOREIGN KEY fk_static_sites_current_release;

DROP TABLE static_site_releases;
DROP TABLE static_sites;
