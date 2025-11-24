package org.congcong.controlmanager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class AccessLogDetail {
    private LocalDateTime ts;
    private String requestId;
    private Long userId;
    private String username;
    private String proxyName;
    private Long inboundId;
    private String clientIp;
    private Integer clientPort;
    private String srcGeoCountry;
    private String srcGeoCity;
    private String originalTargetHost;
    private String originalTargetIP;
    private Integer originalTargetPort;
    private String rewriteTargetHost;
    private Integer rewriteTargetPort;
    private String dstGeoCountry;
    private String dstGeoCity;
    private String inboundProtocolType;
    private String outboundProtocolType;
    private String routePolicyName;
    private Long routePolicyId;
    private Long bytesIn;
    private Long bytesOut;
    private Integer status;
    private String errorCode;
    private String errorMsg;
    private Long requestDurationMs;
    private Long dnsDurationMs;
    private Long connectDurationMs;
    private Long connectTargetDurationMs;
}