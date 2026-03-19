package org.congcong.controlmanager.scheduler;

import lombok.Data;

import java.util.List;

@Data
public class RuleSetSyncTaskConfig {

    private List<Long> ruleSetIds;

    private Boolean enabledOnly = Boolean.TRUE;

    private Boolean publishedOnly = Boolean.FALSE;
}
