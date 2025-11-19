CREATE TABLE IF NOT EXISTS dns_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    domain_pattern VARCHAR(255) NOT NULL COMMENT '域名匹配模式，如 my-next-cloud. 或 .example.com',
    match_type VARCHAR(20) NOT NULL COMMENT '匹配类型：EXACT / SUFFIX',
    record_type VARCHAR(10) NOT NULL COMMENT '记录类型：A / AAAA',
    -- 本地回答用的 IP
    answer_ipv4 VARCHAR(64) NULL COMMENT '本地返回的IPv4（A记录使用）',
    answer_ipv6 VARCHAR(128) NULL COMMENT '本地返回的IPv6（AAAA记录使用）',
    -- 行为控制
    ttl_seconds INT NOT NULL COMMENT 'TTL时间，单位秒',
    action VARCHAR(20) NOT NULL COMMENT '规则动作：LOCAL_ANSWER / FORWARD / BLOCK',
    priority INT NOT NULL COMMENT '优先级，数字越小优先',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用：1启用，0禁用',
    -- 其它
    remark VARCHAR(255) NULL COMMENT '备注',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 常用查询索引：按域名 + 记录类型 + 启用状态查规则
    INDEX idx_dns_rule_domain (domain_pattern, record_type, enabled)
    );
