package org.congcong.proxyworker.outbound.reality.tls;

public final class TlsCertificateVerifyMessage {
    private final int signatureScheme;
    private final byte[] signature;

    public TlsCertificateVerifyMessage(int signatureScheme, byte[] signature) {
        this.signatureScheme = signatureScheme;
        this.signature = copy(signature);
    }

    public static TlsCertificateVerifyMessage fromBody(byte[] body) {
        if (body.length < 4) {
            throw new IllegalArgumentException("TLS CertificateVerify message is too short");
        }
        int signatureScheme = ((body[0] & 0xff) << 8) | (body[1] & 0xff);
        int signatureLength = ((body[2] & 0xff) << 8) | (body[3] & 0xff);
        if (4 + signatureLength > body.length) {
            throw new IllegalArgumentException("TLS CertificateVerify signature is truncated");
        }
        byte[] signature = new byte[signatureLength];
        System.arraycopy(body, 4, signature, 0, signatureLength);
        return new TlsCertificateVerifyMessage(signatureScheme, signature);
    }

    public int signatureScheme() {
        return signatureScheme;
    }

    public byte[] signature() {
        return copy(signature);
    }

    private static byte[] copy(byte[] value) {
        byte[] copy = new byte[value.length];
        System.arraycopy(value, 0, copy, 0, value.length);
        return copy;
    }
}
