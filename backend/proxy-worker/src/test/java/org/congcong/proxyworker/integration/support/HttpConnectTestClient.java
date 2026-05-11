package org.congcong.proxyworker.integration.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

public final class HttpConnectTestClient {
    private HttpConnectTestClient() {
    }

    public static String httpGet(int proxyPort,
                                 String username,
                                 String password,
                                 String targetHost,
                                 int targetPort,
                                 String path) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", proxyPort), (int) Duration.ofSeconds(5).toMillis());
            socket.setSoTimeout((int) Duration.ofSeconds(10).toMillis());
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            out.write(("CONNECT " + targetHost + ":" + targetPort + " HTTP/1.1\r\n"
                    + "Host: " + targetHost + ":" + targetPort + "\r\n"
                    + "Proxy-Authorization: Basic " + token + "\r\n"
                    + "Connection: keep-alive\r\n"
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String headers = readHeaders(in);
            if (!headers.startsWith("HTTP/1.1 200")) {
                throw new IOException("HTTP CONNECT failed: " + headers);
            }

            out.write(("GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + targetHost + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("HTTP CONNECT GET failed", e);
        }
    }

    private static String readHeaders(InputStream in) throws IOException {
        byte[] marker = new byte[] {'\r', '\n', '\r', '\n'};
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int matched = 0;
        int b;
        while ((b = in.read()) != -1) {
            out.write(b);
            matched = b == marker[matched] ? matched + 1 : (b == marker[0] ? 1 : 0);
            if (matched == marker.length) {
                return out.toString(StandardCharsets.US_ASCII);
            }
        }
        throw new IOException("Unexpected EOF before HTTP headers completed");
    }
}
