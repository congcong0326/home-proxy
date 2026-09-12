package org.congcong.proxyworker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementTokenStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void createsTokenOnceAndReusesPersistedValue() throws Exception {
        Path tokenFile = tempDir.resolve("worker-state/management.token");

        String first = ManagementTokenStore.loadOrCreate(tokenFile.toString());
        String second = ManagementTokenStore.loadOrCreate(tokenFile.toString());

        assertTrue(first.startsWith("hpw_"));
        assertEquals(first, second);
        assertEquals(first, Files.readString(tokenFile).trim());
    }
}
