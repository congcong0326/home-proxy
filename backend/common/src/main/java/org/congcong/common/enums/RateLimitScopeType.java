package org.congcong.common.enums;

/**
 * 限流范围类型枚举
 */
public enum RateLimitScopeType {
    GLOBAL("global", "全局"),
    USERS("users", "指定用户");

    private final String value;
    private final String description;

    RateLimitScopeType(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public String getValue() {
        return value;
    }

    public String getDescription() {
        return description;
    }

    public static RateLimitScopeType fromValue(String value) {
        for (RateLimitScopeType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown rate limit scope type value: " + value);
    }
}