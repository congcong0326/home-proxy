package org.congcong.proxyworker.outbound.reality.tls;

public final class TlsCertificateMessage {
    private final byte[] leafDer;

    private TlsCertificateMessage(byte[] leafDer) {
        this.leafDer = copy(leafDer);
    }

    public static TlsCertificateMessage fromBody(byte[] body) {
        int index = 0;
        require(index + 1 <= body.length, "TLS Certificate message missing request context");
        int contextLength = body[index++] & 0xff;
        require(index + contextLength + 3 <= body.length, "TLS Certificate message has invalid request context");
        index += contextLength;
        int listLength = uint24(body, index);
        index += 3;
        require(listLength > 0, "TLS Certificate message did not contain certificates");
        require(index + listLength <= body.length, "TLS Certificate message certificate_list is truncated");
        int certLength = uint24(body, index);
        index += 3;
        require(certLength > 0, "TLS Certificate message leaf certificate is empty");
        require(index + certLength <= body.length, "TLS Certificate message leaf certificate is truncated");
        byte[] leaf = new byte[certLength];
        System.arraycopy(body, index, leaf, 0, certLength);
        return new TlsCertificateMessage(leaf);
    }

    public byte[] leafDer() {
        return copy(leafDer);
    }

    private static int uint24(byte[] bytes, int index) {
        require(index + 3 <= bytes.length, "TLS uint24 is truncated");
        return ((bytes[index] & 0xff) << 16)
                | ((bytes[index + 1] & 0xff) << 8)
                | (bytes[index + 2] & 0xff);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static byte[] copy(byte[] value) {
        byte[] copy = new byte[value.length];
        System.arraycopy(value, 0, copy, 0, value.length);
        return copy;
    }
}
