package org.congcong.common.dto;

import lombok.Data;
import org.congcong.common.enums.RuleSetCategory;
import org.congcong.common.enums.RuleSetMatchTarget;
import org.congcong.common.enums.RuleSetSourceType;

import java.time.LocalDateTime;

@Data
public class RuleSetSummaryDTO {

    private Long id;

    private String ruleKey;

    private String name;

    private RuleSetCategory category;

    private RuleSetMatchTarget matchTarget;

    private RuleSetSourceType sourceType;

    private String sourceConfig;

    private Boolean enabled;

    private Boolean published;

    private Long versionNo;

    private String description;

    private Integer itemCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public RuleSetSummaryDTO(Long id, String ruleKey, String name, RuleSetCategory category,
                             RuleSetMatchTarget matchTarget, RuleSetSourceType sourceType, String sourceConfig,
                             Boolean enabled, Boolean published, Long versionNo, String description,
                             Integer itemCount, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.ruleKey = ruleKey;
        this.name = name;
        this.category = category;
        this.matchTarget = matchTarget;
        this.sourceType = sourceType;
        this.sourceConfig = sourceConfig;
        this.enabled = enabled;
        this.published = published;
        this.versionNo = versionNo;
        this.description = description;
        this.itemCount = itemCount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
