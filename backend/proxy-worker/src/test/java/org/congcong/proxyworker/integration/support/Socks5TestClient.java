package org.congcong.proxyworker.integration.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public final class Socks5TestClient {
    private Socks5TestClient() {
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
            negotiate(in, out, username, password);
            connect(in, out, targetHost, targetPort);
            out.write(("GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + targetHost + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return readAll(in);
        } catch (IOException e) {
            throw new IllegalStateException("SOCKS5 HTTP GET failed", e);
        }
    }

    public static String httpsGet(int proxyPort,
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
            negotiate(in, out, username, password);
            connect(in, out, targetHost, targetPort);

            try (SSLSocket tlsSocket = (SSLSocket) trustAllContext()
                    .getSocketFactory()
                    .createSocket(socket, targetHost, targetPort, true)) {
                tlsSocket.setSoTimeout((int) Duration.ofSeconds(10).toMillis());
                tlsSocket.startHandshake();
                OutputStream tlsOut = tlsSocket.getOutputStream();
                tlsOut.write(("GET " + path + " HTTP/1.1\r\n"
                        + "Host: " + targetHost + "\r\n"
                        + "Connection: close\r\n"
                        + "\r\n").getBytes(StandardCharsets.US_ASCII));
                tlsOut.flush();
                return readHttpResponse(tlsSocket.getInputStream());
            }
        } catch (Exception e) {
            throw new IllegalStateException("SOCKS5 HTTPS GET failed", e);
        }
    }

    public static void expectConnectFailure(int proxyPort,
                                            String username,
                                            String password,
                                            String targetHost,
                                            int targetPort) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", proxyPort), (int) Duration.ofSeconds(5).toMillis());
            socket.setSoTimeout((int) Duration.ofSeconds(5).toMillis());
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            negotiate(in, out, username, password);
            writeConnectRequest(out, targetHost, targetPort);
            byte[] head;
            try {
                head = readN(in, 4);
            } catch (IOException expectedClose) {
                return;
            }
            if ((head[1] & 0xff) == 0x00) {
                throw new AssertionError("Expected SOCKS5 CONNECT failure but got success");
            }
        } catch (IOException e) {
            throw new IllegalStateException("SOCKS5 failure assertion failed", e);
        }
    }

    private static void negotiate(InputStream in, OutputStream out, String username, String password) throws IOException {
        if (username == null) {
            out.write(new byte[] {0x05, 0x01, 0x00});
            out.flush();
            byte[] response = readN(in, 2);
            if (response[0] != 0x05 || response[1] != 0x00) {
                throw new IOException("SOCKS5 no-auth negotiation failed");
            }
            return;
        }

        out.write(new byte[] {0x05, 0x01, 0x02});
        out.flush();
        byte[] response = readN(in, 2);
        if (response[0] != 0x05 || response[1] != 0x02) {
            throw new IOException("SOCKS5 password negotiation failed");
        }
        byte[] user = username.getBytes(StandardCharsets.UTF_8);
        byte[] pass = password.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream auth = new ByteArrayOutputStream();
        auth.write(0x01);
        auth.write(user.length);
        auth.write(user);
        auth.write(pass.length);
        auth.write(pass);
        out.write(auth.toByteArray());
        out.flush();
        byte[] authResponse = readN(in, 2);
        if (authResponse[0] != 0x01 || authResponse[1] != 0x00) {
            throw new IOException("SOCKS5 password auth failed");
        }
    }

    private static void connect(InputStream in, OutputStream out, String targetHost, int targetPort) throws IOException {
        writeConnectRequest(out, targetHost, targetPort);
        byte[] head = readN(in, 4);
        if (head[0] != 0x05 || head[1] != 0x00) {
            throw new IOException("SOCKS5 CONNECT failed with status " + (head[1] & 0xff));
        }
        skipBoundAddress(in, head[3] & 0xff);
    }

    private static void writeConnectRequest(OutputStream out, String targetHost, int targetPort) throws IOException {
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(0x05);
        request.write(0x01);
        request.write(0x00);
        byte[] ip = parseIpv4(targetHost);
        if (ip != null) {
            request.write(0x01);
            request.write(ip);
        } else {
            byte[] host = targetHost.getBytes(StandardCharsets.UTF_8);
            request.write(0x03);
            request.write(host.length);
            request.write(host);
        }
        request.write((targetPort >>> 8) & 0xff);
        request.write(targetPort & 0xff);
        out.write(request.toByteArray());
        out.flush();
    }

    private static void skipBoundAddress(InputStream in, int atyp) throws IOException {
        if (atyp == 0x01) {
            readN(in, 6);
        } else if (atyp == 0x03) {
            int len = in.read();
            readN(in, len + 2);
        } else if (atyp == 0x04) {
            readN(in, 18);
        } else {
            throw new IOException("Unknown SOCKS5 address type: " + atyp);
        }
    }

    private static byte[] parseIpv4(String host) {
        if (!host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return null;
        }
        String[] parts = host.split("\\.");
        byte[] bytes = new byte[4];
        for (int i = 0; i < parts.length; i++) {
            int value = Integer.parseInt(parts[i]);
            if (value < 0 || value > 255) {
                return null;
            }
            bytes[i] = (byte) value;
        }
        return bytes;
    }

    private static byte[] readN(InputStream in, int length) throws IOException {
        byte[] data = in.readNBytes(length);
        if (data.length != length) {
            throw new IOException("Unexpected EOF while reading " + length + " bytes");
        }
        return data;
    }

    private static String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String readHttpResponse(InputStream in) throws IOException {
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        int matched = 0;
        byte[] marker = new byte[] {'\r', '\n', '\r', '\n'};
        while (matched < marker.length) {
            int value = in.read();
            if (value < 0) {
                throw new IOException("Unexpected EOF while reading HTTP response headers");
            }
            headerBytes.write(value);
            matched = value == (marker[matched] & 0xff) ? matched + 1 : (value == '\r' ? 1 : 0);
            if (headerBytes.size() > 64 * 1024) {
                throw new IOException("HTTP response headers exceeded 64 KiB");
            }
        }

        String headers = headerBytes.toString(StandardCharsets.ISO_8859_1);
        int contentLength = contentLength(headers);
        if (contentLength < 0) {
            return headers + readAll(in);
        }
        byte[] body = readN(in, contentLength);
        return headers + new String(body, StandardCharsets.UTF_8);
    }

    private static int contentLength(String headers) {
        for (String line : headers.split("\\r\\n")) {
            int separator = line.indexOf(':');
            if (separator > 0 && "content-length".equalsIgnoreCase(line.substring(0, separator))) {
                return Integer.parseInt(line.substring(separator + 1).trim());
            }
        }
        return -1;
    }

    private static SSLContext trustAllContext() throws Exception {
        TrustManager[] trustManagers = new TrustManager[] {
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers, null);
        return context;
    }
}
