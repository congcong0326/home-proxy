package org.congcong.proxyworker.outbound.reality.tls;

import org.congcong.proxyworker.outbound.reality.config.RealityClientConfig;
import io.netty.buffer.ByteBufUtil;
import java.util.Base64;

public final class RealityParameters {

    private final String publicKey;
    private final String shortIdHex;
    private final byte[] shortIdBytes;

    private RealityParameters(String publicKey, String shortIdHex, byte[] shortIdBytes) {
        this.publicKey = publicKey;
        this.shortIdHex = shortIdHex;
        this.shortIdBytes = shortIdBytes;
    }

    public static RealityParameters from(RealityClientConfig config) {
        String normalized = config.shortId().trim().toLowerCase();
        if ((normalized.length() % 2) != 0) {
            throw new IllegalArgumentException("Invalid REALITY shortId hex length: " + normalized.length());
        }
        if (normalized.length() > 16) {
            throw new IllegalArgumentException("REALITY shortId must be at most 16 hex characters");
        }
        byte[] publicKey = decodeRawUrlBase64(config.publicKey());
        if (publicKey.length != 32) {
            throw new IllegalArgumentException("REALITY publicKey must decode to 32 bytes");
        }
        return new RealityParameters(config.publicKey(), normalized, ByteBufUtil.decodeHexDump(normalized));
    }

    public String publicKey() {
        return publicKey;
    }

    public String shortIdHex() {
        return shortIdHex;
    }

    public byte[] shortIdBytes() {
        return shortIdBytes;
    }

    private static byte[] decodeRawUrlBase64(String value) {
        int padding = (4 - (value.length() % 4)) % 4;
        StringBuilder builder = new StringBuilder(value);
        for (int i = 0; i < padding; i++) {
            builder.append('=');
        }
        return Base64.getUrlDecoder().decode(builder.toString());
    }
}
