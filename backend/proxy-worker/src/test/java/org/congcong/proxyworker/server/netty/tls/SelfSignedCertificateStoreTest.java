package org.congcong.proxyworker.server.netty.tls;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfSignedCertificateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesOnceAndReusesTheSameCertificate() throws Exception {
        Path dir = tempDir.resolve("tls");

        SelfSignedCertificateStore.CertificateFiles first = SelfSignedCertificateStore.loadOrCreate(dir);
        byte[] cert = Files.readAllBytes(first.certFile());
        byte[] key = Files.readAllBytes(first.keyFile());

        SelfSignedCertificateStore.CertificateFiles second = SelfSignedCertificateStore.loadOrCreate(dir);

        assertTrue(first.created());
        assertFalse(second.created());
        assertArrayEquals(cert, Files.readAllBytes(second.certFile()));
        assertArrayEquals(key, Files.readAllBytes(second.keyFile()));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(first.keyFile())));
        try (var files = Files.list(dir)) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void regeneratesWhenOnlyOneFileRemains() throws Exception {
        Path dir = tempDir.resolve("tls");
        SelfSignedCertificateStore.CertificateFiles first = SelfSignedCertificateStore.loadOrCreate(dir);
        byte[] oldCert = Files.readAllBytes(first.certFile());
        Files.delete(first.keyFile());

        SelfSignedCertificateStore.CertificateFiles second = SelfSignedCertificateStore.loadOrCreate(dir);

        assertTrue(second.created());
        assertTrue(Files.isRegularFile(second.keyFile()));
        assertFalse(java.util.Arrays.equals(oldCert, Files.readAllBytes(second.certFile())));
    }
}
