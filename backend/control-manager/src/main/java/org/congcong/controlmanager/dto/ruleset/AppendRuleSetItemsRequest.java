package org.congcong.controlmanager.dto.ruleset;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import org.congcong.common.dto.RuleSetItemDTO;

import java.util.List;

@Data
public class AppendRuleSetItemsRequest {

    @NotEmpty(message = "至少需要提供一条规则项")
    private List<RuleSetItemDTO> items;
}
