CREATE TABLE http_tls_certificates (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    current_version INT NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_http_tls_certificates_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE http_tls_certificate_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    certificate_id BIGINT NOT NULL,
    version INT NOT NULL,
    certificate_chain_pem MEDIUMTEXT NOT NULL,
    encrypted_private_key MEDIUMBLOB NOT NULL,
    encryption_nonce VARBINARY(12) NOT NULL,
    encryption_key_version VARCHAR(32) NOT NULL,
    fingerprint_sha256 CHAR(64) NOT NULL,
    subject_dn VARCHAR(1024) NOT NULL,
    san_entries JSON NOT NULL,
    not_before DATETIME(6) NOT NULL,
    not_after DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_http_tls_certificate_versions_number (certificate_id, version),
    KEY idx_http_tls_certificate_versions_fingerprint (fingerprint_sha256),
    CONSTRAINT fk_http_tls_certificate_versions_certificate
        FOREIGN KEY (certificate_id) REFERENCES http_tls_certificates(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE http_gateways
    ADD CONSTRAINT fk_http_gateways_default_certificate
        FOREIGN KEY (default_certificate_id) REFERENCES http_tls_certificates(id) ON DELETE RESTRICT;

ALTER TABLE http_virtual_hosts
    ADD CONSTRAINT fk_http_virtual_hosts_server_certificate
        FOREIGN KEY (server_certificate_id) REFERENCES http_tls_certificates(id) ON DELETE RESTRICT;
