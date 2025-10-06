package org.congcong.common.enums;

public enum ProtocolType {
    SOCKS5("SOCKS5", "SOCKS5协议"),
    HTTPS_CONNECT("HTTPS_CONNECT", "HTTPS CONNECT协议"),
    SOCKS5_HTTPS("SOCKS5_HTTPS", "SOCKS5+HTTPS混合协议"),
    NONE("NONE", "直接转发"),
    SHADOW_SOCKS("SHADOW_SOCKS", "Shadowsocks协议");
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
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown protocol type value: " + value);
    }
}