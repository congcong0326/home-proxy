-- 为 users 表添加 ip_address 字段并建立唯一约束
ALTER TABLE users
  ADD COLUMN ip_address VARCHAR(45) NULL;

-- 为 ip_address 添加唯一键（允许多个 NULL，确保非空唯一）
ALTER TABLE users
  ADD UNIQUE KEY uk_users_ip_address (ip_address);