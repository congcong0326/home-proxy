package org.congcong.proxyworker.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.integration.support.DnsTestClient;
import org.congcong.proxyworker.integration.support.HttpEchoServer;
import org.congcong.proxyworker.integration.support.NetworkWait;
import org.congcong.proxyworker.integration.support.PortAllocator;
import org.congcong.proxyworker.integration.support.ProxyWorkerIntegrationFixtures;
import org.congcong.proxyworker.integration.support.Socks5TestClient;
import org.congcong.proxyworker.integration.support.WorkerServerHarness;
import org.congcong.proxyworker.integration.support.XrayConfigs;
import org.congcong.proxyworker.integration.support.XrayProcess;
import org.junit.jupiter.api.Test;

class WorkerRoutingIT {

    @Test
    void domainRuleCanSelectOutboundProxyWhileFallbackBlocks() {
        ProxyWorkerIntegrationFixtures.installDomainRulesForTests();
        int workerPort = PortAllocator.tcpPort();
        int xrayPort = PortAllocator.tcpPort();
        RouteConfig matched = ProxyWorkerIntegrationFixtures.outboundRouteForDomain(
                "localhost", org.congcong.common.enums.ProtocolType.SOCKS5, "127.0.0.1", xrayPort);
        RouteConfig fallbackBlock = ProxyWorkerIntegrationFixtures.blockRoute("*");

        try (HttpEchoServer echo = HttpEchoServer.start();
             XrayProcess ignoredXray = XrayProcess.start("routing-socks-upstream", XrayConfigs.socksInbound(xrayPort), xrayPort);
             WorkerServerHarness ignoredWorker = WorkerServerHarness.start(
                     ProxyWorkerIntegrationFixtures.socksInbound(workerPort, List.of(matched), fallbackBlock))) {
            NetworkWait.waitForTcpPort("127.0.0.1", workerPort, java.time.Duration.ofSeconds(5));

            String response = Socks5TestClient.httpGet(workerPort,
                    ProxyWorkerIntegrationFixtures.USERNAME,
                    ProxyWorkerIntegrationFixtures.PASSWORD,
                    "localhost",
                    echo.port(),
                    "/domain-route");
            Socks5TestClient.expectConnectFailure(workerPort,
                    ProxyWorkerIntegrationFixtures.USERNAME,
                    ProxyWorkerIntegrationFixtures.PASSWORD,
                    "127.0.0.1",
                    echo.port());

            assertTrue(response.contains("echo:/domain-route"), response);
            assertEquals(1, echo.requestCount());
        }
    }

    @Test
    void blockRouteRejectsConnectWithoutReachingTarget() {
        int workerPort = PortAllocator.tcpPort();
        RouteConfig block = ProxyWorkerIntegrationFixtures.blockRoute("*");

        try (HttpEchoServer echo = HttpEchoServer.start();
             WorkerServerHarness ignoredWorker = WorkerServerHarness.start(
                     ProxyWorkerIntegrationFixtures.socksInbound(workerPort, List.of(), block))) {
            NetworkWait.waitForTcpPort("127.0.0.1", workerPort, java.time.Duration.ofSeconds(5));

            Socks5TestClient.expectConnectFailure(workerPort,
                    ProxyWorkerIntegrationFixtures.USERNAME,
                    ProxyWorkerIntegrationFixtures.PASSWORD,
                    "127.0.0.1",
                    echo.port());

            assertEquals(0, echo.requestCount());
        }
    }

    @Test
    void dnsRewriteRouteReturnsConfiguredARecord() {
        int workerPort = PortAllocator.udpPort();

        try (WorkerServerHarness ignoredWorker = WorkerServerHarness.start(
                ProxyWorkerIntegrationFixtures.dnsInbound(workerPort,
                        List.of(),
                        ProxyWorkerIntegrationFixtures.dnsRewriteRoute("9.8.7.6")))) {

            List<String> answers = DnsTestClient.queryA(workerPort, "rewrite.example.");

            assertEquals(List.of("9.8.7.6"), answers);
        }
    }
}
