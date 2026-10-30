package org.congcong.proxyworker.udp;

import io.netty.channel.epoll.Epoll;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.ProxyWorkerTestFixtures;
import org.congcong.proxyworker.server.impl.TransparentProxyServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TransparentUdpServerIT {
    @Test void tcpAndUdpSharePortAndCloseTogether() throws Exception {
        assumeTrue(Epoll.isAvailable());
        var inbound = ProxyWorkerTestFixtures.inbound(ProtocolType.TP_PROXY);
        try (ServerSocket reserve = new ServerSocket(0)) { inbound.setPort(reserve.getLocalPort()); }
        TransparentProxyServer server = new TransparentProxyServer(inbound);
        try {
            server.start();
            assertTrue(server.isRunning());
            assertThrows(BindException.class, () -> new DatagramSocket(inbound.getPort(), InetAddress.getLoopbackAddress()));
            var replacement = ProxyWorkerTestFixtures.inbound(ProtocolType.TP_PROXY);
            replacement.setPort(inbound.getPort());
            replacement.setConfigVersion("updated");
            server.updateRuntimeConfig(replacement, replacement);
            assertSame(replacement, server.getRuntimeConfigSnapshot().dnsInboundConfig());
        } finally { server.close(); }
        try (DatagramSocket released = new DatagramSocket(inbound.getPort(), InetAddress.getLoopbackAddress())) {
            assertTrue(released.isBound());
        }
    }

    @Test void udpBindFailureLeavesTcpAvailable() throws Exception {
        assumeTrue(Epoll.isAvailable());
        try (DatagramSocket occupied = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            var inbound = ProxyWorkerTestFixtures.inbound(ProtocolType.TP_PROXY);
            inbound.setPort(occupied.getLocalPort());
            TransparentProxyServer server = new TransparentProxyServer(inbound);
            try {
                server.start();
                assertTrue(server.isRunning());
                assertFalse(server.isUdpRunning());
            } finally { server.close(); }
        }
    }

    @Test void failedUdpListenerIsRetriedOnConfigUpdateWithoutRestartingTcp() throws Exception {
        assumeTrue(Epoll.isAvailable());
        var inbound = ProxyWorkerTestFixtures.inbound(ProtocolType.TP_PROXY);
        TransparentProxyServer server;
        try (DatagramSocket occupied = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            inbound.setPort(occupied.getLocalPort());
            server = new TransparentProxyServer(inbound);
            server.start();
            assertTrue(server.isRunning());
            assertFalse(server.isUdpRunning());
        }
        try {
            server.updateRuntimeConfig(inbound, null);
            assertTrue(server.isRunning());
            assertTrue(server.isUdpRunning());
        } finally { server.close(); }
        assertFalse(server.isUdpRunning());
    }

    @Test void disablingEpollSkipsUdpAndPreservesTcp() throws Exception {
        String previous = System.getProperty("proxyworker.netty.epoll.enabled");
        System.setProperty("proxyworker.netty.epoll.enabled", "false");
        var inbound = ProxyWorkerTestFixtures.inbound(ProtocolType.TP_PROXY);
        try (ServerSocket reserve = new ServerSocket(0)) { inbound.setPort(reserve.getLocalPort()); }
        TransparentProxyServer server = new TransparentProxyServer(inbound);
        try {
            server.start();
            assertTrue(server.isRunning());
            try (DatagramSocket udp = new DatagramSocket(inbound.getPort(), InetAddress.getLoopbackAddress())) {
                assertTrue(udp.isBound());
            }
        } finally {
            server.close();
            if (previous == null) System.clearProperty("proxyworker.netty.epoll.enabled");
            else System.setProperty("proxyworker.netty.epoll.enabled", previous);
        }
    }
}
