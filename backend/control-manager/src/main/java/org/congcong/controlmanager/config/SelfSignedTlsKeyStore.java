package org.congcong.controlmanager.config;

import io.netty.handler.ssl.util.SelfSignedCertificate;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Base64;

/** Creates the ephemeral PKCS12 store used by the embedded management HTTPS server. */
final class SelfSignedTlsKeyStore {
    static final String KEY_ALIAS = "control-manager";
    private static final String CERTIFICATE_HOSTNAME = "home-proxy-control-manager";

    private SelfSignedTlsKeyStore() {
    }

    static GeneratedKeyStore generate() {
        SelfSignedCertificate certificate = null;
        try {
            char[] password = randomPassword();
            certificate = new SelfSignedCertificate(CERTIFICATE_HOSTNAME);

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, password);
            keyStore.setKeyEntry(
                    KEY_ALIAS,
                    certificate.key(),
                    password,
                    new java.security.cert.Certificate[] {certificate.cert()});

            Path path = Files.createTempFile("home-proxy-control-manager-", ".p12");
            path.toFile().deleteOnExit();
            try (OutputStream output = Files.newOutputStream(path)) {
                keyStore.store(output, password);
            }
            return new GeneratedKeyStore(path, new String(password));
        } catch (Exception e) {
            throw new IllegalStateException("无法生成控制端启动自签名 TLS 证书", e);
        } finally {
            if (certificate != null) {
                certificate.delete();
            }
        }
    }

    private static char[] randomPassword() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toCharArray();
    }

    record GeneratedKeyStore(Path path, String password) {
    }
}
