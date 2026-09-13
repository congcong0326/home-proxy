ALTER TABLE routes
    ADD COLUMN outbound_transport VARCHAR(16) NOT NULL DEFAULT 'TCP' AFTER outbound_proxy_type,
    ADD COLUMN outbound_tls_config_json JSON NULL AFTER outbound_transport;
