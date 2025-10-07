package org.congcong.controlmanager.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "access_logs")
public class AccessLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, name = "ts")
    private LocalDateTime ts;

    @Column(name = "request_id", length = 128)
    private String requestId;

    @Column(name = "user_id")
    private Long userId;

    @Column(length = 64)
    private String username;

    @Column(name = "proxy_name", length = 64)
    private String proxyName;

    @Column(name = "inbound_id")
    private Long inboundId;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "client_port")
    private Integer clientPort;

    @Column(name = "src_geo_country", length = 64)
    private String srcGeoCountry;

    @Column(name = "src_geo_city", length = 64)
    private String srcGeoCity;

    @Column(name = "original_target_host", length = 255)
    private String originalTargetHost;

    @Column(name = "original_target_ip", length = 64)
    private String originalTargetIP;

    @Column(name = "original_target_port")
    private Integer originalTargetPort;

    @Column(name = "rewrite_target_host", length = 255)
    private String rewriteTargetHost;

    @Column(name = "rewrite_target_port")
    private Integer rewriteTargetPort;

    @Column(name = "dst_geo_country", length = 64)
    private String dstGeoCountry;

    @Column(name = "dst_geo_city", length = 64)
    private String dstGeoCity;

    @Column(name = "inbound_protocol_type", length = 32)
    private String inboundProtocolType;

    @Column(name = "outbound_protocol_type", length = 32)
    private String outboundProtocolType;

    @Column(name = "route_policy_name", length = 64)
    private String routePolicyName;

    @Column(name = "route_policy_id")
    private Long routePolicyId;

    @Column(name = "bytes_in")
    private Long bytesIn;

    @Column(name = "bytes_out")
    private Long bytesOut;

    @Column(name = "status")
    private Integer status;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_msg", length = 255)
    private String errorMsg;

    @Column(name = "request_duration_ms")
    private Long requestDurationMs;

    @Column(name = "dns_duration_ms")
    private Long dnsDurationMs;

    @Column(name = "connect_duration_ms")
    private Long connectDurationMs;

    @Column(name = "connect_target_duration_ms")
    private Long connectTargetDurationMs;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @PrePersist
    public void prePersist() {
        if (receivedAt == null) {
            receivedAt = LocalDateTime.now();
        }
        if (ts == null) {
            ts = LocalDateTime.now();
        }
    }
}