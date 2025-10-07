package org.congcong.controlmanager.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "auth_logs")
public class AuthLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, name = "ts")
    private LocalDateTime ts;

    @Column(name = "proxy_name", length = 64)
    private String proxyName;

    @Column(name = "inbound_id")
    private Long inboundId;

    @Column(name = "user_id")
    private Long userId;

    @Column(length = 64)
    private String username;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "client_port")
    private Integer clientPort;

    @Column(nullable = false)
    private Boolean success;

    @Column(name = "fail_reason", length = 128)
    private String failReason;

    @Column(name = "protocol", length = 32)
    private String protocol;

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