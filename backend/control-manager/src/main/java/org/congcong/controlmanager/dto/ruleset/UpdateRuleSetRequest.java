package org.congcong.controlmanager.dto.ruleset;

import jakarta.validation.constraints.Size;
import lombok.Data;
import org.congcong.common.dto.RuleSetItemDTO;
import org.congcong.common.enums.RuleSetCategory;
import org.congcong.common.enums.RuleSetMatchTarget;
import org.congcong.common.enums.RuleSetSourceType;

import java.util.List;

@Data
public class UpdateRuleSetRequest {

    @Size(max = 64, message = "规则集 key 长度不能超过64个字符")
    private String ruleKey;

    @Size(max = 128, message = "规则集名称长度不能超过128个字符")
    private String name;

    private RuleSetCategory category;

    private RuleSetMatchTarget matchTarget;

    private RuleSetSourceType sourceType;

    private String sourceConfig;

    private Boolean enabled;

    private Boolean published;

    @Size(max = 255, message = "描述长度不能超过255个字符")
    private String description;

    private List<RuleSetItemDTO> items;
}
