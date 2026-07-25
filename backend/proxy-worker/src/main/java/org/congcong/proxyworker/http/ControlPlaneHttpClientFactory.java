package org.congcong.proxyworker.http;

import org.congcong.proxyworker.config.ProxyWorkerConfig;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/** Shared client for all worker-to-control-plane traffic. */
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
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(5000))
                .sslContext(createSslContext(config.getControlManagerTrustedCertFiles()))
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

    private static SSLContext createSslContext(String certificateFiles) {
        if (certificateFiles == null) throw new IllegalStateException("control.manager.trustedCertFiles 未配置");
        try {
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            int count = 0;
            for (String rawPath : certificateFiles.split(",")) {
                String path = rawPath.trim();
                if (path.isEmpty()) continue;
                try (InputStream input = Files.newInputStream(Path.of(path))) {
                    X509Certificate certificate = (X509Certificate) certificateFactory.generateCertificate(input);
                    trustStore.setCertificateEntry("control-plane-" + count++, certificate);
                }
            }
            if (count == 0) throw new IllegalStateException("未加载任何管理端信任证书");
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagerFactory.getTrustManagers(), null);
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化管理端 TLS 信任链", e);
        }
    }
}
