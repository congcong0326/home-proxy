package org.congcong.controlmanager.dto.ruleset;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.congcong.common.dto.RuleSetItemDTO;
import org.congcong.common.enums.RuleSetCategory;
import org.congcong.common.enums.RuleSetMatchTarget;
import org.congcong.common.enums.RuleSetSourceType;

import java.util.List;

@Data
public class CreateRuleSetRequest {

    @NotBlank(message = "规则集 key 不能为空")
    @Size(max = 64, message = "规则集 key 长度不能超过64个字符")
    private String ruleKey;

    @NotBlank(message = "规则集名称不能为空")
    @Size(max = 128, message = "规则集名称长度不能超过128个字符")
    private String name;

    @NotNull(message = "规则集分类不能为空")
    private RuleSetCategory category;

    @NotNull(message = "匹配目标不能为空")
    private RuleSetMatchTarget matchTarget;

    @NotNull(message = "来源类型不能为空")
    private RuleSetSourceType sourceType;

    private String sourceConfig;

    private Boolean enabled;

    private Boolean published;

    @Size(max = 255, message = "描述长度不能超过255个字符")
    private String description;

    private List<RuleSetItemDTO> items;
}
