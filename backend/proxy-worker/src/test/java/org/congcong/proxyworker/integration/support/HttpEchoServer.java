package org.congcong.proxyworker.integration.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class HttpEchoServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final int port;

    private HttpEchoServer(HttpServer server, ExecutorService executor, int port) {
        this.server = server;
        this.executor = executor;
        this.port = port;
    }

    public static HttpEchoServer start() {
        int port = PortAllocator.tcpPort();
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            ExecutorService executor = Executors.newCachedThreadPool();
            HttpEchoServer echo = new HttpEchoServer(server, executor, port);
            server.createContext("/", echo::handle);
            server.setExecutor(executor);
            server.start();
            NetworkWait.waitForTcpPort("127.0.0.1", port, Duration.ofSeconds(5));
            return echo;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start HTTP echo server", e);
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

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
