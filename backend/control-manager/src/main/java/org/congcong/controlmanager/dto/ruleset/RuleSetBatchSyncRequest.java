package org.congcong.controlmanager.dto.ruleset;

import lombok.Data;

import java.util.List;

@Data
public class RuleSetBatchSyncRequest {

    private List<Long> ruleSetIds;

    private Boolean enabledOnly = Boolean.TRUE;

    private Boolean publishedOnly = Boolean.FALSE;
}
