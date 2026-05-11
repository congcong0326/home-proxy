package org.congcong.proxyworker.outbound.reality.tls;

public final class TlsServerHello {

    private final int cipherSuite;
    private final int keyShareGroup;
    private final byte[] keyShare;

    private TlsServerHello(int cipherSuite, int keyShareGroup, byte[] keyShare) {
        this.cipherSuite = cipherSuite;
        this.keyShareGroup = keyShareGroup;
        this.keyShare = keyShare;
    }

    public static TlsServerHello from(TlsHandshakeMessage message) {
        if (message.type() != TlsHandshakeMessageType.SERVER_HELLO) {
            throw new IllegalArgumentException("Expected ServerHello message");
        }

        byte[] body = message.body();
        int index = 2 + 32;
        int sessionIdLength = body[index] & 0xff;
        index += 1 + sessionIdLength;
        int cipherSuite = unsignedShort(body, index);
        index += 2;
        index += 1;
        int extensionsLength = unsignedShort(body, index);
        index += 2;
        int extensionsEnd = index + extensionsLength;

        while (index + 4 <= extensionsEnd) {
            int extensionType = unsignedShort(body, index);
            int extensionLength = unsignedShort(body, index + 2);
            index += 4;
            if (extensionType == 0x0033) {
                int group = unsignedShort(body, index);
                int keyLength = unsignedShort(body, index + 2);
                byte[] keyShare = new byte[keyLength];
                System.arraycopy(body, index + 4, keyShare, 0, keyLength);
                return new TlsServerHello(cipherSuite, group, keyShare);
            }
            index += extensionLength;
        }

        throw new IllegalArgumentException("ServerHello did not contain key_share");
    }

    private static int unsignedShort(byte[] bytes, int index) {
        return ((bytes[index] & 0xff) << 8) | (bytes[index + 1] & 0xff);
    }

    public int cipherSuite() {
        return cipherSuite;
    }

    public int keyShareGroup() {
        return keyShareGroup;
    }

    public byte[] keyShare() {
        return keyShare;
    }
}
