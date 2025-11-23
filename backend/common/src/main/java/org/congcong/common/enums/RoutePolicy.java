package org.congcong.common.enums;

/**
 * 路由策略枚举
 */
public enum RoutePolicy {
    DIRECT("DIRECT", "直连"),
    DESTINATION_OVERRIDE("DESTINATION_OVERRIDE", "目标重写"),
    BLOCK("BLOCK", "阻断"),
    DNS_REWRITING("DNS_REWRITING", "自定义DNS解析"),
    OUTBOUND_PROXY("OUTBOUND_PROXY", "出站代理");

    private final String value;
    private final String description;

    RoutePolicy(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public String getValue() {
        return value;
    }

    public String getDescription() {
        return description;
    }
}