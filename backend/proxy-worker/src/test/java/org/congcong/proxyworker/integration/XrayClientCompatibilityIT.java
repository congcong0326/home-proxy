package org.congcong.proxyworker.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.integration.support.HttpEchoServer;
import org.congcong.proxyworker.integration.support.NetworkWait;
import org.congcong.proxyworker.integration.support.PortAllocator;
import org.congcong.proxyworker.integration.support.ProxyWorkerIntegrationFixtures;
import org.congcong.proxyworker.integration.support.Socks5TestClient;
import org.congcong.proxyworker.integration.support.WorkerServerHarness;
import org.congcong.proxyworker.integration.support.XrayConfigs;
import org.congcong.proxyworker.integration.support.XrayProcess;
import org.junit.jupiter.api.Test;

class XrayClientCompatibilityIT {

    @Test
    void xraySocksClientCanUseWorkerSocksInbound() {
        int workerPort = PortAllocator.tcpPort();
        int xrayClientPort = PortAllocator.tcpPort();
        RouteConfig direct = ProxyWorkerIntegrationFixtures.directRoute();

        try (HttpEchoServer echo = HttpEchoServer.start();
             WorkerServerHarness ignoredWorker = WorkerServerHarness.start(
                     ProxyWorkerIntegrationFixtures.socksInbound(workerPort, List.of(), direct));
             XrayProcess ignoredXray = XrayProcess.start("xray-client-worker-socks",
                     XrayConfigs.socksClientToWorkerSocks(xrayClientPort,
                             workerPort,
                             ProxyWorkerIntegrationFixtures.USERNAME,
                             ProxyWorkerIntegrationFixtures.PASSWORD),
                     xrayClientPort)) {
            NetworkWait.waitForTcpPort("127.0.0.1", workerPort, java.time.Duration.ofSeconds(5));

            String response = Socks5TestClient.httpGet(xrayClientPort,
                    null,
                    null,
                    "127.0.0.1",
                    echo.port(),
                    "/xray-client-socks");

            assertTrue(response.contains("echo:/xray-client-socks"), response);
            assertEquals(1, echo.requestCount());
        }
    }

    @Test
    void xraySocksClientCanUseWorkerHttpConnectInbound() {
        int workerPort = PortAllocator.tcpPort();
        int xrayClientPort = PortAllocator.tcpPort();
        RouteConfig direct = ProxyWorkerIntegrationFixtures.directRoute();

        try (HttpEchoServer echo = HttpEchoServer.start();
             WorkerServerHarness ignoredWorker = WorkerServerHarness.start(
                     ProxyWorkerIntegrationFixtures.httpInbound(workerPort, List.of(), direct));
             XrayProcess ignoredXray = XrayProcess.start("xray-client-worker-http",
                     XrayConfigs.socksClientToWorkerHttp(xrayClientPort,
                             workerPort,
                             ProxyWorkerIntegrationFixtures.USERNAME,
                             ProxyWorkerIntegrationFixtures.PASSWORD),
                     xrayClientPort)) {
            NetworkWait.waitForTcpPort("127.0.0.1", workerPort, java.time.Duration.ofSeconds(5));

            String response = Socks5TestClient.httpGet(xrayClientPort,
                    null,
                    null,
                    "127.0.0.1",
                    echo.port(),
                    "/xray-client-http");

            assertTrue(response.contains("echo:/xray-client-http"), response);
            assertEquals(1, echo.requestCount());
        }
    }
}
