package org.congcong.controlmanager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class AccessLogListItem {
    private LocalDateTime ts;
    private String requestId;
    private Long userId;
    private String username;
    private String proxyName;
    private Long inboundId;
    private String clientIp;
    private Integer status;
    private Long bytesIn;
    private Long bytesOut;
    private Long requestDurationMs;
    private String originalTargetHost;
    private String rewriteTargetHost;

    private String srcGeoCountry;
    private String dstGeoCountry;

    private String routePolicyName;
    private Long routePolicyId;
}