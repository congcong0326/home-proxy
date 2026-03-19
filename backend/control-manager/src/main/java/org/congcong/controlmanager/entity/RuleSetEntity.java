package org.congcong.controlmanager.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.congcong.common.dto.RuleSetItemDTO;
import org.congcong.common.enums.RuleSetCategory;
import org.congcong.common.enums.RuleSetMatchTarget;
import org.congcong.common.enums.RuleSetSourceType;
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Entity
@Table(name = "rule_set")
public class RuleSetEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_key", nullable = false, length = 64, unique = true)
    private String ruleKey;

    @Column(nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RuleSetCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_target", nullable = false, length = 32)
    private RuleSetMatchTarget matchTarget;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private RuleSetSourceType sourceType;

    @Column(name = "source_config", columnDefinition = "TEXT")
    private String sourceConfig;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(nullable = false)
    private Boolean published = false;

    @Column(name = "version_no", nullable = false)
    private Long versionNo = 1L;

    @Column(length = 255)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "items_json", nullable = false, columnDefinition = "JSON")
    private List<RuleSetItemDTO> items;

    @Formula("coalesce(json_length(items_json), 0)")
    private Integer itemCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (versionNo == null || versionNo < 1) {
            versionNo = 1L;
        }
        if (enabled == null) {
            enabled = true;
        }
        if (published == null) {
            published = false;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
