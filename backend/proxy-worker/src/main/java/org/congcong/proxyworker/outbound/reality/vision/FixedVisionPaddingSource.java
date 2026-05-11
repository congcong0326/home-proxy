package org.congcong.proxyworker.outbound.reality.vision;

public final class FixedVisionPaddingSource implements VisionPaddingSource {
    private final int value;

    public FixedVisionPaddingSource(int value) {
        this.value = value;
    }

    @Override
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        return Math.floorMod(value, bound);
    }
}
