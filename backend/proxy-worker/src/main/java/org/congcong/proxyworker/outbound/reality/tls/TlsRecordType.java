package org.congcong.proxyworker.outbound.reality.tls;

public enum TlsRecordType {
    CHANGE_CIPHER_SPEC(20),
    ALERT(21),
    HANDSHAKE(22),
    APPLICATION_DATA(23);

    private final int code;

    TlsRecordType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static TlsRecordType fromCode(int code) {
        for (TlsRecordType value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown TLS record type: " + code);
    }
}
