package org.congcong.proxyworker.integration;

import com.sun.net.httpserver.HttpServer;
import org.congcong.proxyworker.integration.support.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorkerHttpForwardIT {
    @Test
    void forwardsAbsoluteAndOriginFormAndDoesNotLeakProxyCredentials() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (String target : List.of("http://echo.internal.example:8081/path?q=1", "/path?q=1")) {
                String response = fixture.exchange("GET " + target + " HTTP/1.1\r\n" + auth()
                        + "Host: echo.internal.example:8081\r\nConnection: keep-alive, X-Hop\r\n"
                        + "Proxy-Connection: keep-alive\r\nX-Hop: private\r\n\r\n");
                assertTrue(response.startsWith("HTTP/1.1 200 OK"), response);
                assertTrue(response.contains("GET /path?q=1"), response);
                assertTrue(response.contains("host=echo.internal.example:8081"), response);
                assertTrue(response.contains("proxyAuth=null; proxyConnection=null; hop=null"), response);
            }
            assertEquals(2, fixture.requests.get());
        }
    }

    @Test
    void challengesMissingOrWrongCredentialsBeforeReachingOrigin() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (String credentials : List.of("", "Proxy-Authorization: Basic " + token("it-user:bad") + "\r\n")) {
                String response = fixture.exchange("GET http://echo.internal.example/ HTTP/1.1\r\n"
                        + credentials + "Host: echo.internal.example\r\n\r\n");
                assertTrue(response.startsWith("HTTP/1.1 407"), response);
            }
            assertEquals(0, fixture.requests.get());
        }
    }

    @Test
    void streamsLargeRequestBodyWithExpectContinue() throws Exception {
        try (Fixture fixture = new Fixture(); Socket socket = fixture.connect()) {
            byte[] body = new byte[2 * 1024 * 1024];
            java.util.Arrays.fill(body, (byte) 'x');
            socket.getOutputStream().write(("POST http://echo.internal.example/upload HTTP/1.1\r\n" + auth()
                    + "Host: echo.internal.example\r\nExpect: 100-continue\r\nContent-Length: "
                    + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 100"));
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.startsWith("HTTP/1.1 200 OK"), response);
            assertTrue(response.contains("bodyBytes=" + body.length + "; bodyValid=true"), response);
        }
    }

    @Test
    void forwardsChunkedBodyWithoutForwardingPipelinedSecondRequest() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String response = fixture.exchange("POST http://echo.internal.example/upload HTTP/1.1\r\n" + auth()
                    + "Host: echo.internal.example\r\nTransfer-Encoding: chunked\r\n\r\n"
                    + "3\r\nxxx\r\n2\r\nxx\r\n0\r\n\r\n"
                    + "GET http://other.example/ HTTP/1.1\r\n" + auth() + "Host: other.example\r\n\r\n");
            assertTrue(response.startsWith("HTTP/1.1 200 OK"), response);
            assertTrue(response.contains("bodyBytes=5; bodyValid=true"), response);
            assertEquals(1, fixture.requests.get());
        }
    }

    @Test
    void preservesDirectAddressSafetyAndRouteBlock() throws Exception {
        for (var route : List.of(ProxyWorkerIntegrationFixtures.directRoute(),
                ProxyWorkerIntegrationFixtures.blockRoute("127.0.0.1"))) {
            int port = PortAllocator.tcpPort();
            try (WorkerServerHarness ignored = WorkerServerHarness.start(
                    ProxyWorkerIntegrationFixtures.httpInbound(port, List.of(), route));
                 Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(10000);
                socket.getOutputStream().write(("GET http://127.0.0.1:22/ HTTP/1.1\r\n" + auth()
                        + "Host: 127.0.0.1:22\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                assertTrue(response.startsWith(route.getPolicy() == org.congcong.common.enums.RoutePolicy.DIRECT
                        ? "HTTP/1.1 403" : "HTTP/1.1 502"), response);
            }
        }
    }

    private static String auth() {
        return "Proxy-Authorization: Basic " + token("it-user:it-pass") + "\r\n";
    }

    private static String token(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String readHeaders(InputStream input) throws IOException {
        StringBuilder headers = new StringBuilder();
        int value;
        while ((value = input.read()) != -1) {
            headers.append((char) value);
            if (headers.toString().endsWith("\r\n\r\n")) {
                return headers.toString();
            }
        }
        throw new IOException("Unexpected EOF in HTTP headers: " + headers);
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger();
        final HttpServer origin;
        final WorkerServerHarness worker;
        final int port = PortAllocator.tcpPort();

        Fixture() throws IOException {
            origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            origin.createContext("/", exchange -> {
                requests.incrementAndGet();
                byte[] body = exchange.getRequestBody().readAllBytes();
                boolean valid = true;
                for (byte value : body) {
                    valid &= value == 'x';
                }
                var headers = exchange.getRequestHeaders();
                byte[] response = (exchange.getRequestMethod() + " " + exchange.getRequestURI()
                        + "; host=" + headers.getFirst("Host")
                        + "; proxyAuth=" + headers.getFirst("Proxy-Authorization")
                        + "; proxyConnection=" + headers.getFirst("Proxy-Connection")
                        + "; hop=" + headers.getFirst("X-Hop")
                        + "; bodyBytes=" + body.length + "; bodyValid=" + valid).getBytes(StandardCharsets.UTF_8);
                // 源站按代理发来的 Connection: close 正常结束请求，不额外强制关闭策略。
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            origin.start();
            worker = WorkerServerHarness.start(ProxyWorkerIntegrationFixtures.httpInbound(port, List.of(),
                    ProxyWorkerIntegrationFixtures.destinationOverrideRoute("127.0.0.1", origin.getAddress().getPort())));
        }

        Socket connect() throws IOException {
            Socket socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(10000);
            return socket;
        }

        String exchange(String request) throws IOException {
            try (Socket socket = connect()) {
                socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        @Override
        public void close() {
            worker.close();
            origin.stop(0);
        }
    }
}
