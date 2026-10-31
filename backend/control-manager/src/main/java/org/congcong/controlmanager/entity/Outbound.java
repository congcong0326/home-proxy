package org.congcong.controlmanager.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.ProxyEncAlgo;
import org.congcong.controlmanager.enums.OutboundKind;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Entity
@Table(name = "outbounds")
public class Outbound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OutboundKind kind;

    /** 内置出站为 DIRECT / BLOCK，其余为 null。 */
    @Column(name = "builtin_key", length = 32)
    private String builtinKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "proxy_type")
    private ProtocolType proxyType;

    @Column(length = 255)
    private String host;

    private Integer port;

    @Column(length = 64)
    private String username;

    @Column(length = 255)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(name = "enc_algo")
    private ProxyEncAlgo encAlgo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", columnDefinition = "JSON")
    private Map<String, Object> config;

    @Column(length = 255)
    private String notes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public boolean isBuiltin() {
        return builtinKey != null;
    }

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
