package org.congcong.controlmanager.dto;

import jakarta.validation.constraints.*;

import lombok.Data;
import org.congcong.common.enums.DnsMatchType;
import org.congcong.common.enums.DnsRecordType;
import org.congcong.common.enums.DnsRuleAction;

@Data
public class DnsRuleCreateRequest {

    @NotBlank
    private String domainPattern;

    @NotNull
    private DnsMatchType matchType;

    @NotNull
    private DnsRecordType recordType;

    // 当 action = LOCAL_ANSWER 时，A 记录要求 answerIpv4 不能为空
    private String answerIpv4;

    private String answerIpv6;

    @NotNull
    @Min(1)
    private Integer ttlSeconds;

    @NotNull
    private DnsRuleAction action;

    @NotNull
    private Integer priority;

    @NotNull
    private Boolean enabled;

    private String remark;
}
