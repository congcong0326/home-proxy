package org.congcong.proxyworker.telemetry;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.AttributeKey;
import org.congcong.common.enums.ProtocolType;
import org.congcong.common.telemetry.ConnectOutcome;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.integration.support.ProxyWorkerIntegrationFixtures;
import org.congcong.proxyworker.outbound.OutboundConnector;
import org.congcong.proxyworker.outbound.direct.DirectOutboundConnector;
import org.congcong.proxyworker.outbound.http.HttpProxyOutboundConnector;
import org.congcong.proxyworker.outbound.socks.Socks5OutboundConnector;
import org.congcong.proxyworker.outbound.shadowsocks.ShadowSocksOutboundConnector;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Real socket probes verify timings at protocol boundaries without external services or Xray. */
class OutboundTimingTest {
    @Test
    void directIpHasTcpButNoDnsOrProxyHandshake() throws Exception { probe("direct", "127.0.0.1", false); }
    @Test
    void directHostnameSeparatesSystemDnsFromTcp() throws Exception { probe("direct", "localhost", false); }
    @Test
    void httpConnectMeasuresUntilUpstreamAcknowledges() throws Exception { probe("http", "localhost", false); }
    @Test
    void socks5MeasuresNegotiationAndConnectResponse() throws Exception { probe("socks", "127.0.0.1", false); }
    @Test
    void upstreamRejectionRetainsHandshakeDuration() throws Exception { probe("http", "127.0.0.1", true); }
    @Test
    void shadowsocksMeasuresLocalSendWithoutClaimingRemoteAcknowledgement() throws Exception { probe("ss", "127.0.0.1", false); }

    private void probe(String kind, String host, boolean reject) throws Exception {
        String previous = System.getProperty("proxyworker.netty.epoll.enabled");
        System.setProperty("proxyworker.netty.epoll.enabled", "false");
        EventLoopGroup group = new NioEventLoopGroup(1);
        Channel inbound = null;
        Channel outbound = null;
        CompletableFuture<Void> served = new CompletableFuture<>();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread fixture = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    InputStream input = socket.getInputStream();
                    OutputStream output = socket.getOutputStream();
                    if (kind.equals("http")) {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        while (!bytes.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
                            int value = input.read();
                            if (value < 0) throw new EOFException();
                            bytes.write(value);
                        }
                        output.write((reject ? "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n" : "HTTP/1.1 200 Connection Established\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        output.flush();
                    } else if (kind.equals("socks")) {
                        byte[] greeting = input.readNBytes(2);
                        input.readNBytes(greeting[1] & 255);
                        output.write(new byte[]{5, 0}); output.flush();
                        byte[] command = input.readNBytes(4);
                        int count = switch (command[3]) { case 1 -> 4; case 4 -> 16; default -> input.read(); };
                        input.readNBytes(count + 2);
                        output.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 80}); output.flush();
                    }
                    // Wait for the test to close its outbound socket.
                    while (input.read() >= 0) { }
                    served.complete(null);
                } catch (Throwable failure) { served.completeExceptionally(failure); }
            }, "timing-protocol-fixture");
            fixture.setDaemon(true); fixture.start();
            inbound = new Bootstrap().group(group).channel(NioSocketChannel.class)
                    .handler(new ChannelInboundHandlerAdapter()).register().sync().channel();
            Channel owner = inbound;
            SessionObservation state = new SessionObservation(ignored -> { }, true);
            owner.attr(AttributeKey.<SessionObservation>valueOf("telemetrySessionV1")).set(state);
            int port = server.getLocalPort();
            RouteConfig route = switch (kind) {
                case "http" -> ProxyWorkerIntegrationFixtures.httpOutboundRoute(host, port);
                case "socks" -> ProxyWorkerIntegrationFixtures.socksOutboundRoute(host, port);
                case "ss" -> ProxyWorkerIntegrationFixtures.shadowsocksOutboundRoute(host, port, org.congcong.common.enums.ProxyEncAlgo.aes_128_gcm, "timing-test-secret");
                default -> ProxyWorkerIntegrationFixtures.destinationOverrideRoute(host, port);
            };
            OutboundConnector connector = switch (kind) {
                case "http" -> new HttpProxyOutboundConnector();
                case "socks" -> new Socks5OutboundConnector();
                case "ss" -> new ShadowSocksOutboundConnector();
                default -> new DirectOutboundConnector();
            };
            ProxyTunnelRequest request = new ProxyTunnelRequest(ProtocolType.SOCKS5, "example.test", 80, null, new InboundConfig(), null);
            request.setRouteConfig(route); request.setDnsInboundConfig(null);
            var relay = owner.eventLoop().<Channel>newPromise();
            var connection = owner.eventLoop().submit(() -> {
                TelemetryHooks.beginConnect(owner);
                TelemetryHooks.routing(owner, request, true);
                relay.addListener(done -> {
                    if (done.isSuccess()) TelemetryHooks.connected(owner, (Channel) done.getNow(), request);
                    else TelemetryHooks.connectFailed(owner, done.cause());
                });
                return connector.connect(owner, request, relay);
            }).sync().getNow();
            assertTrue(connection.await(5, TimeUnit.SECONDS));
            outbound = connection.channel();
            assertTrue(relay.await(5, TimeUnit.SECONDS));
            owner.eventLoop().submit(() -> { }).sync();
            assertNotNull(state.summary().getOutboundTcpDurationUs());
            if (host.equals("localhost")) assertNotNull(state.summary().getDnsDurationUs());
            else assertNull(state.summary().getDnsDurationUs());
            assertNull(state.summary().getOutboundTlsDurationUs());
            if (kind.equals("direct")) assertNull(state.summary().getProxyHandshakeDurationUs());
            else {
                assertNotNull(state.summary().getProxyHandshakeDurationUs());
                assertEquals(reject ? "FAILED" : kind.equals("ss") ? "LOCAL_REQUEST_SENT" : "SUCCESS", state.summary().getProxyHandshakeOutcome());
            }
            assertEquals(reject ? ConnectOutcome.FAILED : ConnectOutcome.CONNECTED, state.summary().getConnectOutcome());
            outbound.close().sync(); outbound = null;
            served.get(5, TimeUnit.SECONDS);
        } finally {
            if (outbound != null) outbound.close().syncUninterruptibly();
            if (inbound != null) inbound.close().syncUninterruptibly();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
            if (previous == null) System.clearProperty("proxyworker.netty.epoll.enabled");
            else System.setProperty("proxyworker.netty.epoll.enabled", previous);
        }
    }
}
