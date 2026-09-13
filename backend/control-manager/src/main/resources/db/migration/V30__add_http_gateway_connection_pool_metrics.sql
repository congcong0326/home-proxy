ALTER TABLE http_gateways
    ADD COLUMN upstream_max_idle_connections_per_event_loop INT NOT NULL DEFAULT 16 AFTER idle_timeout_ms;

ALTER TABLE worker_status
    ADD COLUMN http_gateway_client_connection_count INT NULL AFTER active_connection_count,
    ADD COLUMN http_gateway_upstream_active_connection_count INT NULL AFTER http_gateway_client_connection_count,
    ADD COLUMN http_gateway_upstream_idle_connection_count INT NULL AFTER http_gateway_upstream_active_connection_count;
