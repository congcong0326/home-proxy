package org.congcong.controlmanager.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.congcong.common.enums.ProtocolType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Entity
@Table(name = "inbound_configs")
public class InboundConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProtocolType protocol;

    @Column(name = "listen_ip", nullable = false, length = 64)
    private String listenIp;

    @Column(nullable = false)
    private Integer port;

    @Column(name = "tls_enabled", nullable = false)
    private Boolean tlsEnabled = false;

    @Column(name = "sniff_enabled", nullable = false)
    private Boolean sniffEnabled = false;

    @Column(name = "ss_method", length = 64)
    private String ssMethod;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_user_ids", columnDefinition = "JSON")
    private List<String> allowedUserIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "route_ids", columnDefinition = "JSON")
    private List<String> routeIds;

    @Column(nullable = false)
    private Integer status = 1; // 1=enabled, 0=disabled

    @Column(length = 255)
    private String notes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}