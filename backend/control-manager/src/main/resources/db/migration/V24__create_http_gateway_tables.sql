CREATE TABLE http_gateways (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    inbound_id BIGINT NOT NULL,
    default_certificate_id BIGINT NULL,
    egress_route_ids JSON NOT NULL,
    fallback_route_id BIGINT NOT NULL,
    max_initial_line_length INT NOT NULL DEFAULT 8192,
    max_header_size INT NOT NULL DEFAULT 32768,
    max_request_body_bytes BIGINT NOT NULL DEFAULT 2097152,
    connect_timeout_ms INT NOT NULL DEFAULT 5000,
    response_header_timeout_ms INT NOT NULL DEFAULT 15000,
    idle_timeout_ms INT NOT NULL DEFAULT 60000,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_http_gateways_inbound (inbound_id),
    CONSTRAINT fk_http_gateways_inbound FOREIGN KEY (inbound_id) REFERENCES inbound_configs(id) ON DELETE CASCADE,
    CONSTRAINT fk_http_gateways_fallback_route FOREIGN KEY (fallback_route_id) REFERENCES routes(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE http_virtual_hosts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    gateway_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    domains JSON NOT NULL,
    server_certificate_id BIGINT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_http_virtual_hosts_gateway_name (gateway_id, name),
    KEY idx_http_virtual_hosts_gateway_status (gateway_id, status),
    CONSTRAINT fk_http_virtual_hosts_gateway FOREIGN KEY (gateway_id) REFERENCES http_gateways(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE http_locations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    virtual_host_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    path_prefix VARCHAR(2048) NOT NULL,
    priority INT NOT NULL DEFAULT 0,
    action VARCHAR(32) NOT NULL,
    target_host VARCHAR(255) NULL,
    target_port INT NULL,
    static_site_id BIGINT NULL,
    strip_prefix TINYINT NOT NULL DEFAULT 0,
    preserve_host TINYINT NOT NULL DEFAULT 1,
    upstream_host VARCHAR(255) NULL,
    redirect_location VARCHAR(2048) NULL,
    redirect_status INT NULL,
    reject_status INT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_http_locations_virtual_host_name (virtual_host_id, name),
    KEY idx_http_locations_virtual_host_status (virtual_host_id, status),
    CONSTRAINT fk_http_locations_virtual_host FOREIGN KEY (virtual_host_id) REFERENCES http_virtual_hosts(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
