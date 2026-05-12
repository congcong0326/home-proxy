package org.congcong.proxyworker.protocol.shadowsock;

public final class ShadowSocks2022ClientSession {

    private byte[] requestSalt;

    public byte[] getRequestSalt() {
        return requestSalt;
    }

    public void setRequestSalt(byte[] requestSalt) {
        this.requestSalt = requestSalt;
    }
}
