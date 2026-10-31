package org.congcong.controlmanager.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.congcong.common.dto.RouteRule;
import org.congcong.common.enums.RoutePolicy;
import org.congcong.controlmanager.enums.RouteAction;
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
    @Column(length = 32)
    private RouteAction action;

    /** FORWARD 引用的出站；REWRITE_DESTINATION 固定为内置直连；REWRITE_DNS 为空。 */
    @Column(name = "outbound_id")
    private Long outboundId;

    /** 引用改写表时规则为空，表中的域名模式即匹配条件。 */
    @Column(name = "rewrite_table_id")
    private Long rewriteTableId;

    @Column(name = "rewrite_target_host", length = 255)
    private String rewriteTargetHost;

    @Column(name = "rewrite_target_port")
    private Integer rewriteTargetPort;

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
