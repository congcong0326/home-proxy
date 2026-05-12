ALTER TABLE rule_set
  ADD COLUMN item_count INT NOT NULL DEFAULT 0 AFTER description;

UPDATE rule_set
SET item_count = COALESCE(JSON_LENGTH(items_json), 0);

CREATE TABLE IF NOT EXISTS rule_set_payload (
  rule_set_id BIGINT PRIMARY KEY,
  items_json JSON NOT NULL,
  CONSTRAINT fk_rule_set_payload_rule_set
    FOREIGN KEY (rule_set_id) REFERENCES rule_set(id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO rule_set_payload (rule_set_id, items_json)
SELECT id, items_json
FROM rule_set;

ALTER TABLE rule_set
  ADD INDEX idx_rule_set_updated_at_id (updated_at, id),
  ADD INDEX idx_rule_set_enabled_published_updated_at_id (enabled, published, updated_at, id),
  ADD INDEX idx_rule_set_category_enabled_published_updated_at_id (category, enabled, published, updated_at, id);

ALTER TABLE rule_set
  DROP COLUMN items_json;
