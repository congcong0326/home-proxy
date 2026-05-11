package org.congcong.proxyworker.outbound.reality.tls;

public enum HandshakeState {
    IDLE,
    CLIENT_HELLO_SENT,
    SERVER_HELLO_RECEIVED,
    HANDSHAKE_COMPLETE,
    FAILED
}
