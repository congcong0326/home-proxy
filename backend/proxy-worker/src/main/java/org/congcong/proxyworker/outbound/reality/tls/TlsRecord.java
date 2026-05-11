package org.congcong.proxyworker.outbound.reality.tls;

public final class TlsRecord {

    private final TlsRecordType type;
    private final int protocolVersion;
    private final byte[] payload;

    public TlsRecord(TlsRecordType type, int protocolVersion, byte[] payload) {
        this.type = type;
        this.protocolVersion = protocolVersion;
        this.payload = payload;
    }

    public TlsRecordType type() {
        return type;
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    public byte[] payload() {
        return payload;
    }
}
