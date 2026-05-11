package org.congcong.proxyworker.integration.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

public final class NetworkWait {
    private NetworkWait() {
    }

    public static void waitForTcpPort(String host, int port, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 200);
                return;
            } catch (IOException e) {
                last = e;
                sleep();
            }
        }
        throw new IllegalStateException("Timed out waiting for TCP port " + host + ":" + port, last);
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for TCP port", e);
        }
    }
}
