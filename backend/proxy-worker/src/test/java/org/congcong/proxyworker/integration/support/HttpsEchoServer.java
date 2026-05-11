package org.congcong.proxyworker.integration.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

public final class HttpsEchoServer implements AutoCloseable {
    private final HttpsServer server;
    private final ExecutorService executor;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final int port;

    private HttpsEchoServer(HttpsServer server, ExecutorService executor, int port) {
        this.server = server;
        this.executor = executor;
        this.port = port;
    }

    public static HttpsEchoServer start() {
        int port = PortAllocator.tcpPort();
        try {
            HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(sslContext()));
            ExecutorService executor = Executors.newCachedThreadPool();
            HttpsEchoServer echo = new HttpsEchoServer(server, executor, port);
            server.createContext("/", echo::handle);
            server.setExecutor(executor);
            server.start();
            NetworkWait.waitForTcpPort("127.0.0.1", port, Duration.ofSeconds(5));
            return echo;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start HTTPS echo server", e);
        }
    }

    public int port() {
        return port;
    }

    public int requestCount() {
        return requestCount.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        byte[] body = ("echo:" + exchange.getRequestURI().getPath()).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static SSLContext sslContext() throws Exception {
        SelfSignedCertificate certificate = new SelfSignedCertificate("localhost");
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setKeyEntry(
                "localhost",
                certificate.key(),
                "changeit".toCharArray(),
                new java.security.cert.Certificate[] {certificate.cert()});

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, "changeit".toCharArray());

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagerFactory.getKeyManagers(), null, null);
        return context;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
