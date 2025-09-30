package org.congcong.common.enums;

/**
 * 用户状态枚举
 */
public enum UserStatus {
    ENABLED(1, "启用"),
    DISABLED(0, "禁用");

    private final int value;
    private final String description;

    UserStatus(int value, String description) {
        this.value = value;
        this.description = description;
    }

    public int getValue() {
        return value;
    }

    public String getDescription() {
        return description;
    }

    public static UserStatus fromValue(int value) {
        for (UserStatus status : values()) {
            if (status.value == value) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown user status value: " + value);
    }
}