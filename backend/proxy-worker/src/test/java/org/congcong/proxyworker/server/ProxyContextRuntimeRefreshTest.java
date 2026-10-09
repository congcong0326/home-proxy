package org.congcong.proxyworker.server;

import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.dns.FakeIpRegistry;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.enums.RoutePolicy;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProxyContextRuntimeRefreshTest {

    @Test
    void invalidFakeDnsConfigurationKeepsRunningSnapshotAndLeases() throws Exception {
        System.setProperty("proxyworker.netty.epoll.enabled", "false");
        int port = freePort();
        InboundConfig first = socksInbound(port, ProxyWorkerIntegrationFixtures.PASSWORD, 1);
        assertTrue(context.refresh(List.of(first)).success());
        String fake = FakeIpRegistry.getInstance().getOrAllocate("refresh-keeps-lease.example");

        InboundConfig invalidDns = new InboundConfig();
        invalidDns.setId(99999L);
        invalidDns.setProtocol(ProtocolType.DNS_SERVER);
        invalidDns.setFakeIpEnabled(true);
        assertFalse(context.refresh(List.of(first, invalidDns)).success());
        assertEquals("refresh-keeps-lease.example", FakeIpRegistry.getInstance().reverseLookup(fake).orElseThrow());
        try (Socket client = connect(port)) {
            authenticate(client.getInputStream(), client.getOutputStream(), ProxyWorkerIntegrationFixtures.PASSWORD);
        }
    }

    @Test
    void listenerBindFailureRestoresPreviousRunningConfiguration() throws Exception {
        System.setProperty("proxyworker.netty.epoll.enabled", "false");
        int originalPort = freePort();
        InboundConfig first = socksInbound(originalPort, ProxyWorkerIntegrationFixtures.PASSWORD, 1);
        assertTrue(context.refresh(List.of(first)).success());
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            InboundConfig replacement = socksInbound(occupied.getLocalPort(), "replacement", 1);
            assertFalse(context.refresh(List.of(replacement)).success());
            try (Socket client = connect(originalPort)) {
                authenticate(client.getInputStream(), client.getOutputStream(), ProxyWorkerIntegrationFixtures.PASSWORD);
            }
        }
    }

    private final ProxyContext context = ProxyContext.getInstance();

    @Test
    void closedListenerIsReportedDownAndRecreatedWithoutLeakingItsEventLoops() throws Exception {
        System.setProperty("proxyworker.netty.epoll.enabled", "false");
        InboundConfig dns = dnsInbound("listener-recovery");
        assertTrue(context.refresh(List.of(dns)).success());
        AbstractProxyServer original = context.getServerForTest(dns.getId());
        original.serverChannel.close().sync();
        assertFalse(original.isRunning());
        assertEquals(0, context.getRunningServerCount());

        assertTrue(context.refresh(List.of(dns)).success());
        AbstractProxyServer replacement = context.getServerForTest(dns.getId());
        assertNotSame(original, replacement);
        assertTrue(replacement.isRunning());
        assertTrue(original.workerGroup.isTerminated());
    }

    @Test
    void publishesDnsSnapshotOnRefreshAndClearsItOnRemovalAndShutdown() {
        System.setProperty("proxyworker.netty.epoll.enabled", "false");
        InboundConfig first = dnsInbound("first");
        assertTrue(context.refresh(List.of(first)).success());
        assertSame(first, context.getEnabledDnsInbound().orElseThrow());

        InboundConfig second = dnsInbound("second");
        AbstractProxyServer server = context.getServerForTest(first.getId());
        assertTrue(context.refresh(List.of(second)).success());
        assertSame(server, context.getServerForTest(second.getId()));
        assertSame(second, context.getEnabledDnsInbound().orElseThrow());

        assertTrue(context.refresh(List.of()).success());
        assertTrue(context.getEnabledDnsInbound().isEmpty());

        assertTrue(context.refresh(List.of(first)).success());
        context.closeAll();
        assertTrue(context.getEnabledDnsInbound().isEmpty());
    }

    @Test
    void invalidRefreshKeepsPublishedDnsSnapshot() {
        System.setProperty("proxyworker.netty.epoll.enabled", "false");
        InboundConfig first = dnsInbound("first");
        assertTrue(context.refresh(List.of(first)).success());

        InboundConfig duplicate = dnsInbound("duplicate");
        duplicate.setId(first.getId() + 1);
        assertFalse(context.refresh(List.of(first, duplicate)).success());
        assertSame(first, context.getEnabledDnsInbound().orElseThrow());
    }

    @Test
    void failedRefreshRestoresPublishedDnsSnapshot() throws Exception {
        System.setProperty("proxyworker.netty.epoll.enabled", "false");
        InboundConfig first = dnsInbound("first");
        assertTrue(context.refresh(List.of(first)).success());

        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            InboundConfig replacement = dnsInbound("replacement");
            InboundConfig failingTcp = socksInbound(occupied.getLocalPort(), "replacement", 1);
            assertFalse(context.refresh(List.of(replacement, failingTcp)).success());
            assertSame(first, context.getEnabledDnsInbound().orElseThrow());
            assertTrue(context.getServerForTest(first.getId()).isRunning());
        }
    }

    private InboundConfig dnsInbound(String version) {
        InboundConfig inbound = new InboundConfig();
        inbound.setId(99999L);
        inbound.setName("dns-snapshot");
        inbound.setProtocol(ProtocolType.DNS_SERVER);
        inbound.setListenIp("127.0.0.1");
        inbound.setPort(0);
        inbound.setConfigVersion(version);
        return inbound;
    }

    @AfterEach
    void tearDown() {
        context.closeAll();
        System.clearProperty("proxyworker.netty.epoll.enabled");
    }

    @Test
    void delayedSocksRequestKeepsAcceptedDnsSnapshotAcrossRefresh() throws Exception {
        assertDelayedRequestKeepsDnsSnapshot(true);
    }

    @Test
    void delayedSocksRequestDoesNotAdoptDnsEnabledAfterAccept() throws Exception {
        assertDelayedRequestKeepsDnsSnapshot(false);
    }

    private void assertDelayedRequestKeepsDnsSnapshot(boolean initiallyHasDns) throws Exception {
        System.setProperty("proxyworker.netty.epoll.enabled", "false");
        int port = freePort();
        try (TcpEchoServer echo = TcpEchoServer.start()) {
            InboundConfig first = socksInbound(port, ProxyWorkerIntegrationFixtures.PASSWORD, echo.port());
            first.setConfigVersion("delayed-v1");
            first.getDefaultRouteConfig().setOutboundProxyHost("localhost");
            InboundConfig firstDns = dnsRouteInbound("delayed-v1", RoutePolicy.DNS_REWRITING);
            assertTrue(context.refresh(initiallyHasDns ? List.of(first, firstDns) : List.of(first)).success());
            try (Socket client = connect(port)) {
                client.setSoTimeout(3_000);
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();
                // Wait until the connection has captured its snapshot, but delay
                // authentication and the tunnel request until after refresh.
                out.write(new byte[]{0x05, 0x01, 0x02});
                out.flush();
                assertArrayEquals(new byte[]{0x05, 0x02}, readFully(in, 2));

                InboundConfig second = socksInbound(port, "new-password", echo.port());
                second.setConfigVersion("delayed-v2");
                InboundConfig secondDns = dnsRouteInbound("delayed-v2", RoutePolicy.BLOCK);
                assertTrue(context.refresh(List.of(second, secondDns)).success());
                assertSame(secondDns, context.getEnabledDnsInbound().orElseThrow());
                assertSame(secondDns, context.getServerForTest(second.getId())
                        .getRuntimeConfigSnapshot().dnsInboundConfig());

                authenticateCredentials(in, out, ProxyWorkerIntegrationFixtures.PASSWORD);
                connectTunnel(in, out, echo.port());
                assertEcho(in, out, "delayed-after-refresh");
            }
        }
    }

    private InboundConfig dnsRouteInbound(String version, RoutePolicy policy) {
        InboundConfig dns = dnsInbound(version);
        UserConfig anonymous = new UserConfig();
        anonymous.setId(99L);
        dns.setAnonymousUser(anonymous);
        dns.setDeviceIpMapUser(Map.of());
        dns.setRoutesMap(Map.of());
        RouteConfig route = new RouteConfig();
        route.setId(999L);
        route.setPolicy(policy);
        route.setOutboundProxyHost("127.0.0.1");
        dns.setDefaultRouteConfig(route);
        return dns;
    }

    @Test
    void preservesEstablishedTcpTunnelAndUsesUpdatedSnapshotForNewConnections() throws Exception {
        System.setProperty("proxyworker.netty.epoll.enabled", "false");
        int workerPort = freePort();

        try (TcpEchoServer echo = TcpEchoServer.start()) {
            InboundConfig first = socksInbound(workerPort, ProxyWorkerIntegrationFixtures.PASSWORD, echo.port());
            InboundConfig second = socksInbound(workerPort, "updated-password", echo.port());
            second.setName("socks-runtime-updated");
            second.setSniffEnabled(false);
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
        InboundConfig first = socksInbound(freePort(), ProxyWorkerIntegrationFixtures.PASSWORD, 1);
        InboundConfig second = socksInbound(freePort(), ProxyWorkerIntegrationFixtures.PASSWORD, 1);

        assertTrue(context.refresh(List.of(first)).success());
        AbstractProxyServer originalServer = context.getServerForTest(first.getId());

        assertTrue(context.refresh(List.of(second)).success());
        assertNotSame(originalServer, context.getServerForTest(first.getId()));
    }

    private InboundConfig socksInbound(int port, String password, int overridePort) {
        InboundConfig inbound = ProxyWorkerIntegrationFixtures.socksInbound(
                port,
                List.of(),
                ProxyWorkerIntegrationFixtures.destinationOverrideRoute("127.0.0.1", overridePort));
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

        authenticateCredentials(in, out, password);
    }

    private void authenticateCredentials(InputStream in, OutputStream out, String password) throws IOException {
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
