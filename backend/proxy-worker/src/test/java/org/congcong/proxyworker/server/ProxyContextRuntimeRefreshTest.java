package org.congcong.proxyworker.server;

import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.integration.support.ProxyWorkerIntegrationFixtures;
import org.congcong.proxyworker.integration.support.TcpEchoServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyContextRuntimeRefreshTest {

    private final ProxyContext context = ProxyContext.getInstance();

    @AfterEach
    void tearDown() {
        context.closeAll();
        System.clearProperty("proxyworker.netty.epoll.enabled");
    }

    @Test
    void preservesEstablishedTcpTunnelAndUsesUpdatedSnapshotForNewConnections() throws Exception {
        System.setProperty("proxyworker.netty.epoll.enabled", "false");
        int workerPort = freePort();
        InboundConfig first = socksInbound(workerPort, ProxyWorkerIntegrationFixtures.PASSWORD);
        InboundConfig second = socksInbound(workerPort, "updated-password");
        second.setName("socks-runtime-updated");
        second.setSniffEnabled(false);

        try (TcpEchoServer echo = TcpEchoServer.start()) {
            assertTrue(context.refresh(List.of(first)).success());

            try (Socket establishedClient = connect(workerPort)) {
                establishedClient.setSoTimeout(3_000);
                InputStream establishedIn = establishedClient.getInputStream();
                OutputStream establishedOut = establishedClient.getOutputStream();
                authenticate(establishedIn, establishedOut, ProxyWorkerIntegrationFixtures.PASSWORD);
                connectTunnel(establishedIn, establishedOut, echo.port());
                assertEcho(establishedIn, establishedOut, "before-refresh");

                AbstractProxyServer originalServer = context.getServerForTest(first.getId());
                assertTrue(context.refresh(List.of(second)).success());
                assertSame(originalServer, context.getServerForTest(first.getId()));

                assertEcho(establishedIn, establishedOut, "after-refresh");

                try (Socket newClient = connect(workerPort)) {
                    newClient.setSoTimeout(3_000);
                    authenticate(newClient.getInputStream(), newClient.getOutputStream(), "updated-password");
                }
            }
        }
    }

    @Test
    void restartsListenerWhenPortChanges() throws Exception {
        System.setProperty("proxyworker.netty.epoll.enabled", "false");
        InboundConfig first = socksInbound(freePort(), ProxyWorkerIntegrationFixtures.PASSWORD);
        InboundConfig second = socksInbound(freePort(), ProxyWorkerIntegrationFixtures.PASSWORD);

        assertTrue(context.refresh(List.of(first)).success());
        AbstractProxyServer originalServer = context.getServerForTest(first.getId());

        assertTrue(context.refresh(List.of(second)).success());
        assertNotSame(originalServer, context.getServerForTest(first.getId()));
    }

    private InboundConfig socksInbound(int port, String password) {
        InboundConfig inbound = ProxyWorkerIntegrationFixtures.socksInbound(
                port,
                List.of(),
                ProxyWorkerIntegrationFixtures.directRoute());
        UserConfig user = ProxyWorkerIntegrationFixtures.user();
        user.setCredential(password);
        inbound.setUsersMap(Map.of(user.getUsername(), user));
        inbound.setRoutesMap(Map.of(user.getId(), List.of()));
        return inbound;
    }

    private Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port));
        return socket;
    }

    private void authenticate(InputStream in, OutputStream out, String password) throws IOException {
        out.write(new byte[] {0x05, 0x01, 0x02});
        out.flush();
        assertArrayEquals(new byte[] {0x05, 0x02}, readFully(in, 2));

        byte[] username = ProxyWorkerIntegrationFixtures.USERNAME.getBytes(StandardCharsets.UTF_8);
        byte[] credential = password.getBytes(StandardCharsets.UTF_8);
        out.write(0x01);
        out.write(username.length);
        out.write(username);
        out.write(credential.length);
        out.write(credential);
        out.flush();
        assertArrayEquals(new byte[] {0x01, 0x00}, readFully(in, 2));
    }

    private void connectTunnel(InputStream in, OutputStream out, int targetPort) throws IOException {
        out.write(new byte[] {0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1,
                (byte) (targetPort >>> 8), (byte) targetPort});
        out.flush();
        byte[] response = readFully(in, 10);
        assertArrayEquals(new byte[] {0x05, 0x00, 0x00, 0x01},
                new byte[] {response[0], response[1], response[2], response[3]});
    }

    private void assertEcho(InputStream in, OutputStream out, String message) throws IOException {
        byte[] expected = message.getBytes(StandardCharsets.UTF_8);
        out.write(expected);
        out.flush();
        assertArrayEquals(expected, readFully(in, expected.length));
    }

    private byte[] readFully(InputStream in, int length) throws IOException {
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("Unexpected EOF while reading SOCKS response");
        }
        return bytes;
    }

    private int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            return socket.getLocalPort();
        }
    }
}
