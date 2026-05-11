package org.congcong.proxyworker.outbound.reality.vision;

import java.security.SecureRandom;

public final class SecureVisionPaddingSource implements VisionPaddingSource {
    private final SecureRandom random = new SecureRandom();

    @Override
    public int nextInt(int bound) {
        return random.nextInt(bound);
    }
}
