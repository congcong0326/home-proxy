package org.congcong.proxyworker.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.integration.support.DnsTestClient;
import org.congcong.proxyworker.integration.support.FakeDnsServer;
import org.congcong.proxyworker.integration.support.HttpConnectTestClient;
import org.congcong.proxyworker.integration.support.HttpEchoServer;
import org.congcong.proxyworker.integration.support.NetworkWait;
import org.congcong.proxyworker.integration.support.PortAllocator;
import org.congcong.proxyworker.integration.support.ProxyWorkerIntegrationFixtures;
import org.congcong.proxyworker.integration.support.Socks5TestClient;
import org.congcong.proxyworker.integration.support.WorkerServerHarness;
import org.junit.jupiter.api.Test;

class WorkerDirectAndDnsIT {

    @Test
    void socks5InboundCanReachDirectHttpTarget() {
        int workerPort = PortAllocator.tcpPort();
        RouteConfig direct = ProxyWorkerIntegrationFixtures.directRoute();

        try (HttpEchoServer echo = HttpEchoServer.start();
             WorkerServerHarness ignored = WorkerServerHarness.start(
                     ProxyWorkerIntegrationFixtures.socksInbound(workerPort, List.of(), direct))) {
            NetworkWait.waitForTcpPort("127.0.0.1", workerPort, java.time.Duration.ofSeconds(5));

            String response = Socks5TestClient.httpGet(workerPort,
                    ProxyWorkerIntegrationFixtures.USERNAME,
                    ProxyWorkerIntegrationFixtures.PASSWORD,
                    "127.0.0.1",
                    echo.port(),
                    "/direct-socks");

            assertTrue(response.contains("echo:/direct-socks"), response);
            assertEquals(1, echo.requestCount());
        }
    }

    @Test
    void httpConnectInboundCanReachDirectHttpTarget() {
        int workerPort = PortAllocator.tcpPort();
        RouteConfig direct = ProxyWorkerIntegrationFixtures.directRoute();

        try (HttpEchoServer echo = HttpEchoServer.start();
             WorkerServerHarness ignored = WorkerServerHarness.start(
                     ProxyWorkerIntegrationFixtures.httpInbound(workerPort, List.of(), direct))) {
            NetworkWait.waitForTcpPort("127.0.0.1", workerPort, java.time.Duration.ofSeconds(5));

            String response = HttpConnectTestClient.httpGet(workerPort,
                    ProxyWorkerIntegrationFixtures.USERNAME,
                    ProxyWorkerIntegrationFixtures.PASSWORD,
                    "127.0.0.1",
                    echo.port(),
                    "/direct-http");

            assertTrue(response.contains("echo:/direct-http"), response);
            assertEquals(1, echo.requestCount());
        }
    }

    @Test
    void dnsInboundForwardsUdpQueryToConfiguredUpstream() {
        int workerPort = PortAllocator.udpPort();

        try (FakeDnsServer upstream = FakeDnsServer.start("10.20.30.40");
             WorkerServerHarness ignored = WorkerServerHarness.start(
                     ProxyWorkerIntegrationFixtures.dnsInbound(workerPort,
                             List.of(),
                             ProxyWorkerIntegrationFixtures.dnsForwardRoute("127.0.0.1", upstream.port())))) {

            List<String> answers = DnsTestClient.queryA(workerPort, "forward.example.");

            assertEquals(List.of("10.20.30.40"), answers);
            assertEquals(1, upstream.queryCount());
        }
    }
}
