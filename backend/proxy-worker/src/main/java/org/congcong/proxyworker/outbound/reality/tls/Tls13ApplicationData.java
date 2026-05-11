package org.congcong.proxyworker.outbound.reality.tls;

public final class Tls13ApplicationData {

    public TlsRecord encrypt(byte[] payload, TlsRecordType innerContentType, RealityCryptoContext context) {
        byte[] plaintext = new byte[payload.length + 1];
        System.arraycopy(payload, 0, plaintext, 0, payload.length);
        plaintext[plaintext.length - 1] = (byte) innerContentType.code();
        int ciphertextLength = plaintext.length + 16;
        byte[] ciphertext = context.encrypt(plaintext, aad(TlsRecordType.APPLICATION_DATA, 0x0303, ciphertextLength));
        return new TlsRecord(TlsRecordType.APPLICATION_DATA, 0x0303, ciphertext);
    }

    public Tls13Plaintext decrypt(TlsRecord record, RealityCryptoContext context) {
        byte[] plaintext = context.decrypt(record.payload(), aad(record));
        int contentTypeIndex = plaintext.length - 1;
        while (contentTypeIndex >= 0 && plaintext[contentTypeIndex] == 0) {
            contentTypeIndex--;
        }
        if (contentTypeIndex < 0) {
            throw new IllegalArgumentException("TLS 1.3 plaintext did not contain an inner content type");
        }

        byte[] payload = new byte[contentTypeIndex];
        System.arraycopy(plaintext, 0, payload, 0, payload.length);
        return new Tls13Plaintext(
                TlsRecordType.fromCode(plaintext[contentTypeIndex] & 0xff),
                payload);
    }

    private byte[] aad(TlsRecord record) {
        return aad(record.type(), record.protocolVersion(), record.payload().length);
    }

    private byte[] aad(TlsRecordType type, int protocolVersion, int length) {
        return new byte[] {
                (byte) type.code(),
                (byte) ((protocolVersion >>> 8) & 0xff),
                (byte) (protocolVersion & 0xff),
                (byte) ((length >>> 8) & 0xff),
                (byte) (length & 0xff)
        };
    }
}
