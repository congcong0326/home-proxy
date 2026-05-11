package org.congcong.proxyworker.outbound.reality.vision;

import java.util.Arrays;

public final class VisionDecodeResult {
    private final byte[] payload;
    private final boolean direct;

    public VisionDecodeResult(byte[] payload, boolean direct) {
        this.payload = Arrays.copyOf(payload, payload.length);
        this.direct = direct;
    }

    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    public boolean direct() {
        return direct;
    }
}
