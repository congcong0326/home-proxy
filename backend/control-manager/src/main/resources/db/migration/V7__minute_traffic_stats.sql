-- 分钟级流量统计表
CREATE TABLE IF NOT EXISTS agg_minute_traffic_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    minute_time DATETIME NOT NULL COMMENT '分钟时间戳（精确到分钟）',
    user_id BIGINT NULL COMMENT '用户ID，NULL表示全局统计',
    byte_in BIGINT NOT NULL DEFAULT 0 COMMENT '上传字节数',
    byte_out BIGINT NOT NULL DEFAULT 0 COMMENT '下载字节数',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_minute_traffic_time (user_id, minute_time)
)
