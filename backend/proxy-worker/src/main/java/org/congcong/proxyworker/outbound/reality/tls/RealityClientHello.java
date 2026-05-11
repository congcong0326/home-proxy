package org.congcong.proxyworker.outbound.reality.tls;

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;

public final class RealityClientHello {

    private final byte[] payload;
    private final X25519PrivateKeyParameters privateKey;
    private final byte[] authKey;

    public RealityClientHello(byte[] payload, X25519PrivateKeyParameters privateKey, byte[] authKey) {
        this.payload = payload;
        this.privateKey = privateKey;
        this.authKey = copy(authKey);
    }

    public byte[] payload() {
        return payload;
    }

    public X25519PrivateKeyParameters privateKey() {
        return privateKey;
    }

    public byte[] authKey() {
        return copy(authKey);
    }

    private static byte[] copy(byte[] value) {
        byte[] copy = new byte[value.length];
        System.arraycopy(value, 0, copy, 0, value.length);
        return copy;
    }
}
