package org.congcong.controlmanager.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RoutePolicy;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Entity
@Table(name = "routes")
public class Route {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rules_json", nullable = false, columnDefinition = "JSON")
    private List<RouteRule> rules;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RoutePolicy policy;

    @Column(name = "outbound_tag", length = 64)
    private String outboundTag;

    @Enumerated(EnumType.STRING)
    @Column(name = "outbound_proxy_type", nullable = false)
    private ProtocolType outboundProxyType;

    @Column(name = "outbound_proxy_host", length = 255)
    private String outboundProxyHost;

    @Column(name = "outbound_proxy_port")
    private Integer outboundProxyPort;

    @Column(name = "outbound_proxy_username", length = 64)
    private String outboundProxyUsername;

    @Column(name = "outbound_proxy_password", length = 255)
    private String outboundProxyPassword;

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