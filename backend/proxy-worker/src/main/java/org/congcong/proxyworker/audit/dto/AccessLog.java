package org.congcong.proxyworker.audit.dto;

import lombok.Data;
import java.time.Instant;

/**
 * 访问日志
 * 来源参考 ProxyContext 与 ProxyTimeContext 字段。
 */
@Data
public class AccessLog {
    // 时间戳与请求标识
    private Instant ts = Instant.now();
    private String requestId; // 可选：便于去重与追踪

    // 用户与代理信息
    private Long userId;
    private String username;
    private String proxyName;
    private Long inboundId; // 入站配置ID

    // 源地址
    private String clientIp;
    private Integer clientPort;
    private String srcGeoCountry;
    private String srcGeoCity;

    // 目标地址（原始与改写）
    private String originalTargetHost;
    private String originalTargetIP;
    private Integer originalTargetPort;
    private String rewriteTargetHost;
    private Integer rewriteTargetPort;
    private String dstGeoCountry;
    private String dstGeoCity;

    // 协议与路由
    private String inboundProtocolType;
    private String outboundProtocolType;
    private String routePolicyName;
    private Long routePolicyId;

    // 流量与状态
    private Long bytesIn;
    private Long bytesOut;
    private Integer status; // 访问结果状态（如CONNECT/SOCKS握手成功=200，失败为错误码）
    private String errorCode; // 可选错误码
    private String errorMsg;  // 可选错误信息

    // 时延信息（来自 ProxyTimeContext）
    private Long requestDurationMs;
    private Long dnsDurationMs;
    private Long connectDurationMs;
    private Long connectTargetDurationMs;
}