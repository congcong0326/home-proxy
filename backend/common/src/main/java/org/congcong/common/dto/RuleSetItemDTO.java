package org.congcong.common.dto;

import lombok.Data;
import org.congcong.common.enums.RuleSetItemType;

@Data
public class RuleSetItemDTO {

    private RuleSetItemType type;

    private String value;
}
