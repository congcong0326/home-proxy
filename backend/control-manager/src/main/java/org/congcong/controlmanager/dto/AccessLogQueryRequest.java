package org.congcong.controlmanager.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccessLogQueryRequest {
    private String from; // ISO8601或毫秒时间戳
    private String to;   // ISO8601或毫秒时间戳
    private Long userId;
    private String username;
    private String proxyName;
    private Long inboundId;
    private String clientIp;
    private Integer status;
    private String protocol; // inbound/outbound
    private Long routePolicyId;
    private String routePolicyName;
    private String srcGeoCountry;
    private String srcGeoCity;
    private String dstGeoCountry;
    private String dstGeoCity;
    private String host; // 可匹配original/rewrite
    private String originalTargetHost;
    private String rewriteTargetHost;
    private String q; // 自由文本
    private Integer page = 0;
    private Integer size = 20;
    private String sort; // 如 ts,desc
}