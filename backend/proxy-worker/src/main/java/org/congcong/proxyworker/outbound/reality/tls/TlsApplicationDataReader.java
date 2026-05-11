package org.congcong.proxyworker.outbound.reality.tls;

public interface TlsApplicationDataReader {
    Tls13Plaintext decryptApplicationPlaintext(TlsRecord record);

    byte[] decryptApplicationData(TlsRecord record);
}
