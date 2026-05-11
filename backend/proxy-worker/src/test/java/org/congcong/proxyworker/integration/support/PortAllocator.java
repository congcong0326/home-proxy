package org.congcong.proxyworker.integration.support;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;

public final class PortAllocator {
    private PortAllocator() {
    }

    public static int tcpPort() {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to allocate TCP port", e);
        }
    }

    public static int udpPort() {
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to allocate UDP port", e);
        }
    }
}
