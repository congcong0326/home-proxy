CREATE TABLE static_sites (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    current_release_id BIGINT NULL,
    index_document VARCHAR(255) NOT NULL DEFAULT 'index.html',
    spa_fallback_enabled TINYINT NOT NULL DEFAULT 1,
    default_cache_control VARCHAR(255) NOT NULL DEFAULT 'public, max-age=300',
    status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_static_sites_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE static_site_releases (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    site_id BIGINT NOT NULL,
    version INT NOT NULL,
    original_file_name VARCHAR(255) NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    archive_size_bytes BIGINT NOT NULL,
    extracted_size_bytes BIGINT NOT NULL,
    file_count INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_static_site_releases_version (site_id, version),
    KEY idx_static_site_releases_site_status (site_id, status),
    CONSTRAINT fk_static_site_releases_site
        FOREIGN KEY (site_id) REFERENCES static_sites(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE static_sites
    ADD CONSTRAINT fk_static_sites_current_release
        FOREIGN KEY (current_release_id) REFERENCES static_site_releases(id) ON DELETE SET NULL;

ALTER TABLE http_locations
    ADD CONSTRAINT fk_http_locations_static_site
        FOREIGN KEY (static_site_id) REFERENCES static_sites(id) ON DELETE RESTRICT;
