package org.congcong.common.dto;

import lombok.Data;
import java.time.Instant;

/**
 * 用户认证日志
 * 记录工作节点上的用户认证事件（成功/失败）。
 */
@Data
public class AuthLog {
    // 基本信息
    private Instant ts = Instant.now();
    private String proxyName; // 代理服务名称
    private Long inboundId;   // 入站配置ID

    // 用户与客户端信息
    private Long userId;
    private String username;
    private String clientIp;
    private Integer clientPort;

    // 认证结果
    private boolean success;
    private String failReason; // 失败原因（可选）

    // 额外信息
    private String protocol; // 入站协议，如 socks5/http
}