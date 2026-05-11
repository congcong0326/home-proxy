package org.congcong.proxyworker.outbound.reality.tls;

public final class TlsHandshakeMessage {

    private final TlsHandshakeMessageType type;
    private final byte[] body;

    public TlsHandshakeMessage(TlsHandshakeMessageType type, byte[] body) {
        this.type = type;
        this.body = body;
    }

    public TlsHandshakeMessageType type() {
        return type;
    }

    public byte[] body() {
        return body;
    }
}
