package org.congcong.proxyworker.outbound.reality.vless;

public enum VlessFlow {
    NONE(""),
    XTLS_RPRX_VISION("xtls-rprx-vision");

    private final String wireName;

    VlessFlow(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static VlessFlow fromWireName(String value) {
        if (value == null || value.trim().isEmpty()) {
            return NONE;
        }
        for (VlessFlow flow : values()) {
            if (flow.wireName.equals(value.trim())) {
                return flow;
            }
        }
        throw new IllegalArgumentException("Unsupported VLESS flow: " + value);
    }
}
