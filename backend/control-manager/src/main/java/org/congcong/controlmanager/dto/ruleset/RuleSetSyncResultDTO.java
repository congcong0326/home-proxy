package org.congcong.controlmanager.dto.ruleset;

import lombok.Data;

@Data
public class RuleSetSyncResultDTO {

    private Long ruleSetId;

    private String ruleKey;

    private String name;

    private RuleSetSyncStatus status;

    private Long versionNo;

    private Integer itemCount;

    private String message;
}
