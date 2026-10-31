package org.congcong.proxyworker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void configuredTokenTakesPrecedenceAndIsNotPersisted() {
        Path tokenFile = tempDir.resolve("worker-state/management.token");

        String token = ManagementTokenStore.resolve(" hpw_fixed-value ", tokenFile.toString());

        assertEquals("hpw_fixed-value", token);
        assertFalse(Files.exists(tokenFile));
    }

    @Test
    void blankConfiguredTokenFallsBackToPersistedFile() {
        Path tokenFile = tempDir.resolve("worker-state/management.token");

        String token = ManagementTokenStore.resolve("  ", tokenFile.toString());

        assertTrue(token.startsWith("hpw_"));
        assertTrue(Files.exists(tokenFile));
    }

    @Test
    void configuredTokenWithoutPrefixIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> ManagementTokenStore.resolve("not-a-worker-token", tempDir.resolve("t").toString()));
    }
}
