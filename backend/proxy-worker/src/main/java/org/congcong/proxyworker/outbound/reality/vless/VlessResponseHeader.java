package org.congcong.proxyworker.outbound.reality.vless;

public final class VlessResponseHeader {

    private final boolean complete;
    private final byte[] payload;

    public VlessResponseHeader(boolean complete, byte[] payload) {
        this.complete = complete;
        this.payload = payload;
    }

    public boolean complete() {
        return complete;
    }

    public byte[] payload() {
        return payload;
    }
}
