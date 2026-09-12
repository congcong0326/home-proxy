package org.congcong.proxyworker.http;

import org.congcong.proxyworker.config.ProxyWorkerConfig;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/** Shared client for all worker-to-control-plane traffic.
 *
 * <p>The control plane creates an ephemeral self-signed certificate at each start. Workers therefore
 * deliberately skip certificate-chain and hostname verification, while still requiring HTTPS and a
 * per-worker bearer token.</p>
 */
public final class ControlPlaneHttpClientFactory {
    private static final AtomicReference<ControlPlaneHttpClientFactory> INSTANCE = new AtomicReference<>();

    private final HttpClient httpClient;
    private final String token;

    private ControlPlaneHttpClientFactory(ProxyWorkerConfig config) {
        URI uri = URI.create(config.getControlBaseUrl());
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("control.manager.url 必须使用 https");
        }
        this.token = readToken(config.getWorkerTokenFile());
        // HttpClient otherwise enables HTTPS hostname verification when no algorithm is supplied.
        // This JDK switch is intentionally process-wide because all worker-to-control-plane calls
        // share this client and the control-plane certificate is regenerated on every restart.
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(5000))
                .sslContext(createInsecureSslContext())
                .build();
    }

    public static ControlPlaneHttpClientFactory getInstance() {
        ControlPlaneHttpClientFactory current = INSTANCE.get();
        if (current != null) return current;
        ControlPlaneHttpClientFactory created = new ControlPlaneHttpClientFactory(ProxyWorkerConfig.getInstance());
        return INSTANCE.compareAndSet(null, created) ? created : INSTANCE.get();
    }

    public HttpClient getHttpClient() { return httpClient; }

    public HttpRequest.Builder requestBuilder(URI uri) {
        return HttpRequest.newBuilder().uri(uri).header("Authorization", "Bearer " + token);
    }

    private static String readToken(String tokenFile) {
        if (tokenFile == null) throw new IllegalStateException("worker.tokenFile 未配置");
        try {
            String value = Files.readString(Path.of(tokenFile), StandardCharsets.UTF_8).trim();
            if (!value.startsWith("hpw_") || value.length() <= 4) throw new IllegalStateException("Worker Token 格式无效");
            return value;
        } catch (Exception e) {
            throw new IllegalStateException("无法读取 Worker Token 文件", e);
        }
    }

    private static SSLContext createInsecureSslContext() {
        try {
            TrustManager[] trustManagers = {new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    // The worker is never a TLS client-auth endpoint.
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    // Intentionally trust the control-plane's startup-generated self-signed certificate.
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers, new SecureRandom());
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化管理端非校验 TLS 客户端", e);
        }
    }
}
