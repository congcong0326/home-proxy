package org.congcong.proxyworker.outbound.reality.tls;

public enum TlsHandshakeMessageType {
    CLIENT_HELLO(1),
    SERVER_HELLO(2),
    NEW_SESSION_TICKET(4),
    ENCRYPTED_EXTENSIONS(8),
    CERTIFICATE(11),
    CERTIFICATE_VERIFY(15),
    FINISHED(20);

    private final int code;

    TlsHandshakeMessageType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static TlsHandshakeMessageType fromCode(int code) {
        for (TlsHandshakeMessageType value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown handshake message type: " + code);
    }
}
