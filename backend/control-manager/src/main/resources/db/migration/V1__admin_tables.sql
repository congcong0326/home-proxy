-- Flyway V1: 管理员会话相关表

-- 管理员账号表
CREATE TABLE IF NOT EXISTS admin_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    roles VARCHAR(255) NOT NULL,
    must_change_password TINYINT(1) NOT NULL DEFAULT 1,
    ver INT NOT NULL DEFAULT 1,
    status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_admin_user_username (username)
);
-- 默认管理员账号将在应用启动时自动创建
-- 登录历史
CREATE TABLE IF NOT EXISTS admin_login_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NULL,
    username VARCHAR(64) NOT NULL,
    success TINYINT(1) NOT NULL,
    ip VARCHAR(64) NULL,
    ua VARCHAR(255) NULL,
    reason VARCHAR(128) NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_admin_login_user (user_id, created_at),
    INDEX idx_admin_login_name (username, created_at)
);

-- Token 黑名单（可选）
CREATE TABLE IF NOT EXISTS admin_token_blacklist (
    jti VARCHAR(64) PRIMARY KEY,
    expires_at DATETIME NOT NULL,
    INDEX idx_admin_token_exp (expires_at)
);