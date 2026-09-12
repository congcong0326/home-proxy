package org.congcong.controlmanager.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.security.KeyStore;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfSignedTlsKeyStoreTest {

    @Test
    void generatesReadablePkcs12KeyStore() throws Exception {
        SelfSignedTlsKeyStore.GeneratedKeyStore generated = SelfSignedTlsKeyStore.generate();
        try {
            assertTrue(Files.isReadable(generated.path()));

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(generated.path())) {
                keyStore.load(input, generated.password().toCharArray());
            }

            assertTrue(keyStore.isKeyEntry(SelfSignedTlsKeyStore.KEY_ALIAS));
            assertNotNull(keyStore.getCertificate(SelfSignedTlsKeyStore.KEY_ALIAS));
        } finally {
            Files.deleteIfExists(generated.path());
        }
    }
}
