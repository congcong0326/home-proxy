package org.congcong.proxyworker.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProxyWorkerConfigTest {

    private static final String[] CONFIG_KEYS = {
            "CONTROL_MANAGER_URL",
            "CONTROL_HOST",
            "CONTROL_PORT",
            "PROXY_WORKER_CONFIG",
            "TLS_CERT_FILE",
            "TLS_KEY_FILE",
            "TLS_KEY_PASSWORD",
            "TLS_ALLOW_SELF_SIGNED",
            "WORKER_ID",
            "WORKER_TOKEN_FILE",
            "LAST_KNOWN_GOOD_CONFIG",
            "WORKER_MANAGEMENT_ENABLED",
            "WORKER_MANAGEMENT_PORT",
            "WORKER_MANAGEMENT_TOKEN_FILE",
            "WORKER_MANAGEMENT_POLL_INTERVAL_MS"
    };

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        clearSystemProperties();
        resetConfigSingleton();
    }

    @AfterEach
    void tearDown() throws Exception {
        clearSystemProperties();
        resetConfigSingleton();
    }

    @Test
    void externalConfigOverridesClasspathDefaultsAndSystemPropertiesTakePriority() throws Exception {
        Path externalConfig = tempDir.resolve("proxy-worker.properties");
        Files.writeString(externalConfig, """
                control.host=file-control
                control.port=18083
                worker.id=file-worker
                tls.certFile=/mounted/cert.pem
                tls.keyFile=/mounted/key.pem
                tls.keyPassword=file-secret
                """);
        System.setProperty("PROXY_WORKER_CONFIG", externalConfig.toString());
        System.setProperty("WORKER_ID", "env-worker");

        ProxyWorkerConfig config = ProxyWorkerConfig.getInstance();

        assertEquals("env-worker", config.getWorkerId());
        assertEquals("/mounted/cert.pem", config.getTlsCertFile());
        assertEquals("/mounted/key.pem", config.getTlsKeyFile());
        assertEquals("file-secret", config.getTlsKeyPassword());
    }

    @Test
    void legacyHttpSettingsAreIgnored() {
        System.setProperty("CONTROL_MANAGER_URL", "http://10.0.0.5:18081/");
        System.setProperty("CONTROL_HOST", "control.local");
        System.setProperty("CONTROL_PORT", "not-a-number");
        System.setProperty("WORKER_TOKEN_FILE", "/missing/worker.token");
        System.setProperty("WORKER_CONTROL_POLL_INTERVAL_MS", "bad");
        System.setProperty("WORKER_MANAGEMENT_ENABLED", "false");

        ProxyWorkerConfig config = ProxyWorkerConfig.getInstance();

        assertEquals("default", config.getWorkerId());
    }

    @Test
    void managementChannelSettingsCanBeOverriddenAtRuntime() {
        System.setProperty("WORKER_MANAGEMENT_PORT", "19444");
        System.setProperty("WORKER_MANAGEMENT_TOKEN_FILE", "/state/management.token");
        System.setProperty("WORKER_MANAGEMENT_POLL_INTERVAL_MS", "2500");

        ProxyWorkerConfig config = ProxyWorkerConfig.getInstance();

        assertEquals(19444, config.getManagementPort());
        assertEquals("/state/management.token", config.getManagementTokenFile());
        assertEquals(2500L, config.getManagementPollIntervalMs());
    }

    private static void clearSystemProperties() {
        for (String key : CONFIG_KEYS) {
            System.clearProperty(key);
        }
    }

    private static void resetConfigSingleton() throws Exception {
        Field instance = ProxyWorkerConfig.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }
}
