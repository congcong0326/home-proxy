package org.congcong.common.dto;

import lombok.Data;
import org.congcong.common.enums.RuleSetCategory;
import org.congcong.common.enums.RuleSetMatchTarget;
import org.congcong.common.enums.RuleSetSourceType;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class RuleSetDTO {

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

    private List<RuleSetItemDTO> items;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
