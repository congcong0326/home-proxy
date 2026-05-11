package org.congcong.proxyworker.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.congcong.common.enums.ProxyEncAlgo;
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

class WorkerOutboundProxyIT {

    @Test
    void socks5InboundCanUseXraySocksOutbound() {
        int workerPort = PortAllocator.tcpPort();
        int xrayPort = PortAllocator.tcpPort();
        RouteConfig outbound = ProxyWorkerIntegrationFixtures.socksOutboundRoute("127.0.0.1", xrayPort);

        try (HttpEchoServer echo = HttpEchoServer.start();
             XrayProcess ignoredXray = XrayProcess.start("socks-upstream", XrayConfigs.socksInbound(xrayPort), xrayPort);
             WorkerServerHarness ignoredWorker = WorkerServerHarness.start(
                     ProxyWorkerIntegrationFixtures.socksInbound(workerPort, List.of(), outbound))) {
            NetworkWait.waitForTcpPort("127.0.0.1", workerPort, java.time.Duration.ofSeconds(5));

            String response = Socks5TestClient.httpGet(workerPort,
                    ProxyWorkerIntegrationFixtures.USERNAME,
                    ProxyWorkerIntegrationFixtures.PASSWORD,
                    "127.0.0.1",
                    echo.port(),
                    "/xray-socks-out");

            assertTrue(response.contains("echo:/xray-socks-out"), response);
            assertEquals(1, echo.requestCount());
        }
    }

    @Test
    void socks5InboundCanUseXrayHttpConnectOutbound() {
        int workerPort = PortAllocator.tcpPort();
        int xrayPort = PortAllocator.tcpPort();
        RouteConfig outbound = ProxyWorkerIntegrationFixtures.httpOutboundRoute("127.0.0.1", xrayPort);

        try (HttpEchoServer echo = HttpEchoServer.start();
             XrayProcess ignoredXray = XrayProcess.start("http-upstream", XrayConfigs.httpInbound(xrayPort), xrayPort);
             WorkerServerHarness ignoredWorker = WorkerServerHarness.start(
                     ProxyWorkerIntegrationFixtures.socksInbound(workerPort, List.of(), outbound))) {
            NetworkWait.waitForTcpPort("127.0.0.1", workerPort, java.time.Duration.ofSeconds(5));

            String response = Socks5TestClient.httpGet(workerPort,
                    ProxyWorkerIntegrationFixtures.USERNAME,
                    ProxyWorkerIntegrationFixtures.PASSWORD,
                    "127.0.0.1",
                    echo.port(),
                    "/xray-http-out");

            assertTrue(response.contains("echo:/xray-http-out"), response);
            assertEquals(1, echo.requestCount());
        }
    }

    @Test
    void socks5InboundCanUseXrayShadowsocksOutbound() {
        int workerPort = PortAllocator.tcpPort();
        int xrayPort = PortAllocator.tcpPort();
        String password = "shadow-pass";
        RouteConfig outbound = ProxyWorkerIntegrationFixtures.shadowsocksOutboundRoute(
                "127.0.0.1", xrayPort, ProxyEncAlgo.aes_128_gcm, password);

        try (HttpEchoServer echo = HttpEchoServer.start();
             XrayProcess ignoredXray = XrayProcess.start("shadowsocks-upstream",
                     XrayConfigs.shadowsocksInbound(xrayPort, "aes-128-gcm", password),
                     xrayPort);
             WorkerServerHarness ignoredWorker = WorkerServerHarness.start(
                     ProxyWorkerIntegrationFixtures.socksInbound(workerPort, List.of(), outbound))) {
            NetworkWait.waitForTcpPort("127.0.0.1", workerPort, java.time.Duration.ofSeconds(5));

            String response = Socks5TestClient.httpGet(workerPort,
                    ProxyWorkerIntegrationFixtures.USERNAME,
                    ProxyWorkerIntegrationFixtures.PASSWORD,
                    "127.0.0.1",
                    echo.port(),
                    "/xray-ss-out");

            assertTrue(response.contains("echo:/xray-ss-out"), response);
            assertEquals(1, echo.requestCount());
        }
    }
}
