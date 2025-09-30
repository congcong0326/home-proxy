package org.congcong.common.enums;

/**
 * 协议类型枚举
 */
public enum ProtocolType {
    SOCKS5("socks5", "SOCKS5协议"),
    HTTPS_CONNECT("https_connect", "HTTPS CONNECT协议"),
    SOCKS5_HTTPS("socks5_https", "SOCKS5+HTTPS混合协议"),
    SS("ss", "Shadowsocks协议");

    private final String value;
    private final String description;

    ProtocolType(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public String getValue() {
        return value;
    }

    public String getDescription() {
        return description;
    }

    public static ProtocolType fromValue(String value) {
        for (ProtocolType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown protocol type value: " + value);
    }
}