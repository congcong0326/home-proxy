package org.congcong.proxyworker.integration;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.congcong.common.telemetry.SessionLogV1;
import org.congcong.proxyworker.integration.support.*;
import org.congcong.proxyworker.protocol.http.HttpServerInitializer;
import org.congcong.proxyworker.protocol.socks.SocksServerInitializer;
import org.congcong.proxyworker.telemetry.TelemetryHooks;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Run with -Dobservability.enabled=true to verify the actual accept/protocol/router/relay chain. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "observability.enabled", matches = "true")
class WorkerSessionTimingIT {
    @Test void socks5CollectsHandshakeRoutingDnsTcpAndRelayTimes() throws Exception { verify(false, false); }
    @Test void httpConnectCollectsHandshakeRoutingDnsTcpAndRelayTimes() throws Exception { verify(true, false); }
    @Test void authenticationFailureMeasuresHandshakeWithoutInventingOutboundStages() throws Exception { verify(true, true); }

    private void verify(boolean http, boolean invalidCredentials) throws Exception {
        String previous = System.getProperty("proxyworker.netty.epoll.enabled");
        System.setProperty("proxyworker.netty.epoll.enabled", "false");
        EventLoopGroup group = new NioEventLoopGroup(1);
        Channel server = null;
        CompletableFuture<SessionLogV1> summary = new CompletableFuture<>();
        try (HttpEchoServer echo = HttpEchoServer.start()) {
            var route = ProxyWorkerIntegrationFixtures.destinationOverrideRoute("localhost", echo.port());
            var inbound = http ? ProxyWorkerIntegrationFixtures.httpInbound(0, List.of(), route)
                    : ProxyWorkerIntegrationFixtures.socksInbound(0, List.of(), route);
            ChannelHandler initializer = http ? new HttpServerInitializer(inbound) {
                @Override protected void pipeLineContextInit(Channel ch) {
                    super.pipeLineContextInit(ch); capture(ch, summary);
                }
            } : new SocksServerInitializer(inbound) {
                @Override protected void pipeLineContextInit(Channel ch) {
                    super.pipeLineContextInit(ch); capture(ch, summary);
                }
            };
            server = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                    .childHandler(initializer).bind("127.0.0.1", 0).sync().channel();
            int port = ((java.net.InetSocketAddress) server.localAddress()).getPort();
            if (invalidCredentials) {
                assertEquals(407, HttpConnectTestClient.connectStatus(port, ProxyWorkerIntegrationFixtures.USERNAME,
                        "invalid-password", "example.test", echo.port()));
            } else {
                String response = http ? HttpConnectTestClient.httpGet(port, ProxyWorkerIntegrationFixtures.USERNAME,
                        ProxyWorkerIntegrationFixtures.PASSWORD, "example.test", echo.port(), "/timing")
                        : Socks5TestClient.httpGet(port, ProxyWorkerIntegrationFixtures.USERNAME,
                        ProxyWorkerIntegrationFixtures.PASSWORD, "example.test", echo.port(), "/timing");
                assertTrue(response.contains("echo:/timing"));
            }
            SessionLogV1 record = summary.get(5, TimeUnit.SECONDS);
            assertNotNull(record.getClientHandshakeDurationUs());
            if (invalidCredentials) {
                assertNull(record.getRouteSelectionDurationUs());
                assertNull(record.getDnsDurationUs());
                assertNull(record.getOutboundTcpDurationUs());
                assertNull(record.getUploadBackpressureDurationUs());
            } else {
                assertNotNull(record.getRouteSelectionDurationUs());
                assertNotNull(record.getDnsDurationUs());
                assertNotNull(record.getOutboundTcpDurationUs());
                assertNotNull(record.getUploadBackpressureDurationUs());
                assertNotNull(record.getDownloadBackpressureDurationUs());
                assertTrue(record.getUploadBytes() > 0);
                assertTrue(record.getDownloadBytes() > 0);
            }
            assertNull(record.getTargetRecoveryDurationUs());
            assertNull(record.getOutboundTlsDurationUs());
            assertNull(record.getProxyHandshakeDurationUs());
        } finally {
            if (server != null) server.close().syncUninterruptibly();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
            if (previous == null) System.clearProperty("proxyworker.netty.epoll.enabled");
            else System.setProperty("proxyworker.netty.epoll.enabled", previous);
        }
    }

    private void capture(Channel channel, CompletableFuture<SessionLogV1> summary) {
        var state = TelemetryHooks.state(channel);
        assertNotNull(state, "Run this IT with -Dobservability.enabled=true");
        channel.closeFuture().addListener(done -> channel.eventLoop().execute(() -> summary.complete(state.summary())));
    }
}
