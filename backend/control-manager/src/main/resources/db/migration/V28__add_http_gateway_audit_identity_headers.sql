ALTER TABLE http_gateways
    ADD COLUMN audit_username_response_header VARCHAR(128) NOT NULL DEFAULT 'X-Authenticated-User' AFTER idle_timeout_ms,
    ADD COLUMN audit_user_id_response_header VARCHAR(128) NOT NULL DEFAULT 'X-Authenticated-User-Id' AFTER audit_username_response_header,
    ADD COLUMN audit_default_username VARCHAR(128) NOT NULL DEFAULT 'unknown' AFTER audit_user_id_response_header;
