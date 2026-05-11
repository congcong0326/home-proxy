package org.congcong.proxyworker.outbound.reality.tls;

public final class Tls13Plaintext {

    private final TlsRecordType contentType;
    private final byte[] payload;

    public Tls13Plaintext(TlsRecordType contentType, byte[] payload) {
        this.contentType = contentType;
        this.payload = payload;
    }

    public TlsRecordType contentType() {
        return contentType;
    }

    public byte[] payload() {
        return payload;
    }
}
