package org.congcong.controlmanager.dto;

import lombok.Data;
import org.congcong.common.enums.DnsMatchType;
import org.congcong.common.enums.DnsRecordType;
import org.congcong.common.enums.DnsRuleAction;

@Data
public class DnsRuleUpdateRequest {

    // 哪条规则
    private Long id;

    private String domainPattern;

    private DnsMatchType matchType;

    private DnsRecordType recordType;

    private String answerIpv4;

    private String answerIpv6;

    private Integer ttlSeconds;

    private DnsRuleAction action;

    private Integer priority;

    private Boolean enabled;

    private String remark;
}
