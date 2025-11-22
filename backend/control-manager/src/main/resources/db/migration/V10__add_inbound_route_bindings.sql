ALTER TABLE inbound_configs
    ADD COLUMN inbound_route_bindings JSON NULL AFTER route_ids;