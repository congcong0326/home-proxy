package org.congcong.proxyworker.outbound.reality.tls;

public interface TlsApplicationDataWriter {
    TlsRecord encryptApplicationData(byte[] payload);
}
