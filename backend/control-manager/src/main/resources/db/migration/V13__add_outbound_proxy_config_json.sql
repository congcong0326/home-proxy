ALTER TABLE routes
  ADD COLUMN outbound_proxy_config_json JSON NULL AFTER outbound_proxy_enc_algo;
