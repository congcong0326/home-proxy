package org.congcong.common.enums;

public enum DnsRuleAction {
    LOCAL_ANSWER,  // 本地构造应答
    FORWARD,       // 直接丢给上游 DNS
    BLOCK          // 拒绝 / NXDOMAIN
}
