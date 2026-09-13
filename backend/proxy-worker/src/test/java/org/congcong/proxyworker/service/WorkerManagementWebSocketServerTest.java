package org.congcong.proxyworker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.congcong.common.dto.WorkerManagementMessage;
import org.congcong.common.dto.WorkerManagementMessageType;
import org.congcong.proxyworker.config.ProxyWorkerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerManagementWebSocketServerTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanUp() throws Exception {
        System.clearProperty("WORKER_ID");
        System.clearProperty("WORKER_MANAGEMENT_ENABLED");
        System.clearProperty("WORKER_MANAGEMENT_PORT");
        System.clearProperty("WORKER_MANAGEMENT_TOKEN_FILE");
        Field instance = ProxyWorkerConfig.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Test
    void acceptsAuthorizedWssClientAndSendsConfigPoll() throws Exception {
        resetConfigSingleton();
        int port = availablePort();
        Path tokenFile = tempDir.resolve("management.token");
        System.setProperty("WORKER_ID", "wss-test");
        System.setProperty("WORKER_MANAGEMENT_ENABLED", "true");
        System.setProperty("WORKER_MANAGEMENT_PORT", String.valueOf(port));
        System.setProperty("WORKER_MANAGEMENT_TOKEN_FILE", tokenFile.toString());

        WorkerManagementWebSocketServer server = new WorkerManagementWebSocketServer(new AggregateConfigService());
        server.start();
        try {
            String token = Files.readString(tokenFile).trim();
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<WorkerManagementMessage> message = new AtomicReference<>();
            WebSocket socket = trustedClient().newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + token)
                    .buildAsync(URI.create("wss://localhost:" + port + "/management/ws"), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(1);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            if (last) {
                                try {
                                    message.set(new ObjectMapper().readValue(data.toString(), WorkerManagementMessage.class));
                                    received.countDown();
                                } catch (Exception ignored) {
                                    // The assertion below turns an unparsable frame into a test failure.
                                }
                            }
                            webSocket.request(1);
                            return CompletableFuture.completedFuture(null);
                        }
                    }).join();

            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertEquals(WorkerManagementMessageType.CONFIG_POLL, message.get().getType());
            assertEquals("wss-test", message.get().getWorkerId());
            assertTrue(message.get().getHeartbeat() != null);
            assertTrue(message.get().getHeartbeat().getMetrics() != null);
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        } finally {
            server.stop();
        }
    }

    private int availablePort() throws Exception {
        try (ServerSocket socket = ServerSocketFactory.getDefault().createServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void resetConfigSingleton() throws Exception {
        Field instance = ProxyWorkerConfig.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private HttpClient trustedClient() throws Exception {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        TrustManager[] trustManagers = {new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagers, new SecureRandom());
        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm(null);
        return HttpClient.newBuilder().sslContext(sslContext).sslParameters(sslParameters).build();
    }
}
