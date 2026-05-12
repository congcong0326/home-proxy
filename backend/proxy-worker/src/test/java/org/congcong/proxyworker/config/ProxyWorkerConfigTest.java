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
            "TLS_KEY_PASSWORD"
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
    void controlManagerUrlSystemPropertyOverridesClasspathDefaults() throws Exception {
        System.setProperty("CONTROL_MANAGER_URL", "http://10.0.0.5:18081/");

        ProxyWorkerConfig config = ProxyWorkerConfig.getInstance();

        assertEquals("10.0.0.5", config.getControlHost());
        assertEquals(18081, config.getControlPort());
        assertEquals("http://10.0.0.5:18081", config.getControlBaseUrl());
        assertEquals("http://10.0.0.5:18081/api/config/aggregate", config.getAggregateConfigUrl());
        assertEquals("http://10.0.0.5:18081/api/logs/access", config.getAccessLogUrl());
    }

    @Test
    void controlHostAndPortSystemPropertiesOverrideClasspathDefaults() throws Exception {
        System.setProperty("CONTROL_HOST", "control.local");
        System.setProperty("CONTROL_PORT", "18082");

        ProxyWorkerConfig config = ProxyWorkerConfig.getInstance();

        assertEquals("control.local", config.getControlHost());
        assertEquals(18082, config.getControlPort());
        assertEquals("http://control.local:18082", config.getControlBaseUrl());
    }

    @Test
    void externalConfigOverridesClasspathDefaultsAndSystemPropertiesTakePriority() throws Exception {
        Path externalConfig = tempDir.resolve("proxy-worker.properties");
        Files.writeString(externalConfig, """
                control.host=file-control
                control.port=18083
                tls.certFile=/mounted/cert.pem
                tls.keyFile=/mounted/key.pem
                tls.keyPassword=file-secret
                """);
        System.setProperty("PROXY_WORKER_CONFIG", externalConfig.toString());
        System.setProperty("CONTROL_PORT", "18084");

        ProxyWorkerConfig config = ProxyWorkerConfig.getInstance();

        assertEquals("file-control", config.getControlHost());
        assertEquals(18084, config.getControlPort());
        assertEquals("http://file-control:18084", config.getControlBaseUrl());
        assertEquals("/mounted/cert.pem", config.getTlsCertFile());
        assertEquals("/mounted/key.pem", config.getTlsKeyFile());
        assertEquals("file-secret", config.getTlsKeyPassword());
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
