package org.congcong.proxyworker.http;

import org.congcong.proxyworker.config.ProxyWorkerConfig;
import org.congcong.proxyworker.integration.support.HttpsEchoServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlPlaneHttpClientFactoryTest {
    private static final String[] CONFIG_KEYS = {
            "CONTROL_MANAGER_URL",
            "WORKER_TOKEN_FILE",
            "jdk.internal.httpclient.disableHostnameVerification"
    };

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        clearSystemProperties();
        resetSingletons();
    }

    @AfterEach
    void tearDown() throws Exception {
        clearSystemProperties();
        resetSingletons();
    }

    @Test
    void acceptsAnUntrustedCertificateAndHostname() throws Exception {
        Path tokenFile = tempDir.resolve("worker.token");
        Files.writeString(tokenFile, "hpw_test_token");

        try (HttpsEchoServer server = HttpsEchoServer.start()) {
            System.setProperty("CONTROL_MANAGER_URL", "https://127.0.0.1:" + server.port());
            System.setProperty("WORKER_TOKEN_FILE", tokenFile.toString());

            ControlPlaneHttpClientFactory factory = ControlPlaneHttpClientFactory.getInstance();
            HttpResponse<String> response = factory.getHttpClient().send(
                    factory.requestBuilder(URI.create("https://127.0.0.1:" + server.port() + "/control-plane")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals("echo:/control-plane", response.body());
        }
    }

    private static void clearSystemProperties() {
        for (String key : CONFIG_KEYS) {
            System.clearProperty(key);
        }
    }

    private static void resetSingletons() throws Exception {
        Field configInstance = ProxyWorkerConfig.class.getDeclaredField("instance");
        configInstance.setAccessible(true);
        configInstance.set(null, null);

        Field clientInstance = ControlPlaneHttpClientFactory.class.getDeclaredField("INSTANCE");
        clientInstance.setAccessible(true);
        ((AtomicReference<?>) clientInstance.get(null)).set(null);
    }
}
