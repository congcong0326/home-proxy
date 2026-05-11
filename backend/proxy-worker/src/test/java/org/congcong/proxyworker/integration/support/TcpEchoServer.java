package org.congcong.proxyworker.integration.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TcpEchoServer implements AutoCloseable {
    private final ServerSocket serverSocket;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private TcpEchoServer(ServerSocket serverSocket, ExecutorService executor) {
        this.serverSocket = serverSocket;
        this.executor = executor;
    }

    public static TcpEchoServer start() {
        try {
            ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            ExecutorService executor = Executors.newCachedThreadPool();
            TcpEchoServer server = new TcpEchoServer(socket, executor);
            executor.execute(server::acceptLoop);
            NetworkWait.waitForTcpPort("127.0.0.1", socket.getLocalPort(), Duration.ofSeconds(5));
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start TCP echo server", e);
        }
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                executor.execute(() -> echo(socket));
            } catch (IOException e) {
                if (running.get()) {
                    throw new IllegalStateException("TCP echo accept failed", e);
                }
            }
        }
    }

    private void echo(Socket socket) {
        try (socket; InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream()) {
            in.transferTo(out);
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        running.set(false);
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        executor.shutdownNow();
    }
}
