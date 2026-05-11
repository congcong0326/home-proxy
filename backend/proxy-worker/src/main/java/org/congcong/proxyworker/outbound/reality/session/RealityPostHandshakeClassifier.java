package org.congcong.proxyworker.outbound.reality.session;

public final class RealityPostHandshakeClassifier {
    public enum Result {
        NEED_MORE_BYTES,
        VLESS_RESPONSE,
        CAMOUFLAGE
    }

    public Result classify(byte[] plaintext) {
        if (plaintext.length < 2) {
            return Result.NEED_MORE_BYTES;
        }
        if ((plaintext[0] & 0xff) == 0x00) {
            int addonsLength = plaintext[1] & 0xff;
            return plaintext.length >= 2 + addonsLength
                    ? Result.VLESS_RESPONSE
                    : Result.NEED_MORE_BYTES;
        }
        return Result.CAMOUFLAGE;
    }
}
