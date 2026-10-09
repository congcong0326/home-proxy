ALTER TABLE inbound_configs
    ADD COLUMN fake_ip_enabled TINYINT(1) NOT NULL DEFAULT 0 AFTER sniff_enabled;
