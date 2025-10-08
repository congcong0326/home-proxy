-- 日度聚合统计表

-- 用户（日度）流量统计
CREATE TABLE IF NOT EXISTS agg_day_user_stats (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  day_date DATE NOT NULL,
  user_id BIGINT NOT NULL,
  username VARCHAR(64) NULL,
  requests_count BIGINT NOT NULL DEFAULT 0,
  bytes_in BIGINT NOT NULL DEFAULT 0,
  bytes_out BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_user_day (day_date, user_id),
  INDEX idx_user_day (day_date, user_id, username)
);

-- 应用（日度）流量统计
CREATE TABLE IF NOT EXISTS agg_day_app_stats (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  day_date DATE NOT NULL,
  target_host VARCHAR(255) NOT NULL,
  requests_count BIGINT NOT NULL DEFAULT 0,
  bytes_in BIGINT NOT NULL DEFAULT 0,
  bytes_out BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_app_day (day_date, target_host),
  INDEX idx_app_day (day_date, target_host)
);

-- 用户-应用（日度）访问流量统计
CREATE TABLE IF NOT EXISTS agg_day_user_app_stats (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  day_date DATE NOT NULL,
  user_id BIGINT NOT NULL,
  username VARCHAR(64) NULL,
  target_host VARCHAR(255) NOT NULL,
  requests_count BIGINT NOT NULL DEFAULT 0,
  bytes_in BIGINT NOT NULL DEFAULT 0,
  bytes_out BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_user_app_day (day_date, user_id, target_host),
  INDEX idx_user_app_day (day_date, user_id, target_host)
);

-- 源地理位置（日度）访问次数统计
CREATE TABLE IF NOT EXISTS agg_day_src_geo_stats (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  day_date DATE NOT NULL,
  src_geo_country VARCHAR(64) NULL,
  src_geo_city VARCHAR(64) NULL,
  requests_count BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_src_geo_day (day_date, src_geo_country, src_geo_city),
  INDEX idx_src_geo_day (day_date, src_geo_country, src_geo_city)
);

-- 目标地理位置（日度）访问次数统计
CREATE TABLE IF NOT EXISTS agg_day_dst_geo_stats (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  day_date DATE NOT NULL,
  dst_geo_country VARCHAR(64) NULL,
  dst_geo_city VARCHAR(64) NULL,
  requests_count BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_dst_geo_day (day_date, dst_geo_country, dst_geo_city),
  INDEX idx_dst_geo_day (day_date, dst_geo_country, dst_geo_city)
);