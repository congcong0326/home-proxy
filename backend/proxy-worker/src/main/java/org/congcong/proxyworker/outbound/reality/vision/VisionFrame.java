package org.congcong.proxyworker.outbound.reality.vision;

import java.util.Arrays;

public final class VisionFrame {
    private final VisionCommand command;
    private final byte[] payload;

    public VisionFrame(VisionCommand command, byte[] payload) {
        this.command = command;
        this.payload = Arrays.copyOf(payload, payload.length);
    }

    public VisionCommand command() {
        return command;
    }

    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
