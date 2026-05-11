package org.congcong.proxyworker.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.congcong.proxyworker.config.RouteConfig;
import org.congcong.proxyworker.integration.support.HttpsEchoServer;
import org.congcong.proxyworker.integration.support.NetworkWait;
import org.congcong.proxyworker.integration.support.PortAllocator;
import org.congcong.proxyworker.integration.support.ProxyWorkerIntegrationFixtures;
import org.congcong.proxyworker.integration.support.Socks5TestClient;
import org.congcong.proxyworker.integration.support.WorkerServerHarness;
import org.congcong.proxyworker.integration.support.XrayConfigs;
import org.congcong.proxyworker.integration.support.XrayProcess;
import org.junit.jupiter.api.Test;

class WorkerVlessRealityVisionIT {
    private static final String UUID = "11111111-1111-1111-1111-111111111111";
    private static final String PRIVATE_KEY = "ALedq9JMkfnGtIIzDUJPbDWpneoLo7dPUuCtWTzO_lE";
    private static final String PUBLIC_KEY = "aBGATVQ1VTz68_l5yT8jzCEuRLG52Oe4AXhLw-PJUSg";
    private static final String SHORT_ID = "6ba85179e30d4fc2";
    private static final String SERVER_NAME = "localhost";

    @Test
    void socks5InboundCanUseXrayVlessRealityVisionOutboundForHttpsTarget() {
        int workerPort = PortAllocator.tcpPort();
        int xrayPort = PortAllocator.tcpPort();
        RouteConfig outbound = ProxyWorkerIntegrationFixtures.vlessRealityOutboundRoute(
                "127.0.0.1",
                xrayPort,
                SERVER_NAME,
                PUBLIC_KEY,
                SHORT_ID,
                UUID);

        try (HttpsEchoServer echo = HttpsEchoServer.start();
             XrayProcess ignoredXray = XrayProcess.start("vless-reality-vision-upstream",
                     XrayConfigs.vlessRealityVisionInbound(
                             xrayPort,
                             UUID,
                             PRIVATE_KEY,
                             SHORT_ID,
                             SERVER_NAME,
                             "localhost:" + echo.port()),
                     xrayPort);
             WorkerServerHarness ignoredWorker = WorkerServerHarness.start(
                     ProxyWorkerIntegrationFixtures.socksInbound(workerPort, List.of(), outbound))) {
            NetworkWait.waitForTcpPort("127.0.0.1", workerPort, Duration.ofSeconds(5));

            String response = Socks5TestClient.httpsGet(
                    workerPort,
                    ProxyWorkerIntegrationFixtures.USERNAME,
                    ProxyWorkerIntegrationFixtures.PASSWORD,
                    "localhost",
                    echo.port(),
                    "/xray-vless-reality-vision-out");

            assertTrue(response.contains("echo:/xray-vless-reality-vision-out"), response);
            assertEquals(1, echo.requestCount());
        }
    }
}
