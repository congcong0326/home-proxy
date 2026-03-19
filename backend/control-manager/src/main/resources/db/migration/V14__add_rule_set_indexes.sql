ALTER TABLE rule_set
  ADD INDEX idx_rule_set_updated_at (updated_at),
  ADD INDEX idx_rule_set_category_updated_at (category, updated_at),
  ADD INDEX idx_rule_set_enabled_published_updated_at (enabled, published, updated_at);
