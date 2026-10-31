package org.congcong.proxyworker.server.netty.tls;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import lombok.extern.slf4j.Slf4j;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.ProxyWorkerConfig;

import javax.net.ssl.SSLException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 管理入站端的 TLS 上下文。
 * 配置了外部证书时使用外部证书；否则使用持久化在数据目录的自签证书（首次生成，重启复用）。
 * 所有 TLS 入站共用同一个 Server SslContext。
 */
@Slf4j
public class TlsContextManager {

    private static final TlsContextManager INSTANCE = new TlsContextManager();
    private volatile SslContext serverContext;

    private TlsContextManager() {}

    public static TlsContextManager getInstance() {
        return INSTANCE;
    }

    /**
     * 获取（或创建）入站 Server 端 SslContext。
     */
    public SslContext getServerContext(InboundConfig inboundConfig) {
        SslContext context = serverContext;
        if (context == null) {
            synchronized (this) {
                context = serverContext;
                if (context == null) {
                    context = createServerContext(ProxyWorkerConfig.getInstance());
                    serverContext = context;
                }
            }
        }
        return context;
    }

    private SslContext createServerContext(ProxyWorkerConfig cfg) {
        if (isBlank(cfg.getTlsCertFile()) && isBlank(cfg.getTlsKeyFile())) {
            SelfSignedCertificateStore.CertificateFiles files =
                    SelfSignedCertificateStore.loadOrCreate(Path.of(cfg.getTlsSelfSignedDir()));
            log.info("{}入站 TLS 自签证书: {}", files.created() ? "已生成" : "复用", files.certFile());
            return buildServerContext(files.certFile().toFile(), files.keyFile().toFile(), null);
        }
        SslContext external = createExternalContext(cfg);
        log.info("入站 TLS 使用外部证书: {}", cfg.getTlsCertFile());
        return external;
    }

    /**
     * 从配置中读取外部证书与私钥路径创建 SslContext；显式配置但不可用时直接失败，不回退自签。
     */
    private SslContext createExternalContext(ProxyWorkerConfig cfg) {
        String certPath = cfg.getTlsCertFile();
        String keyPath = cfg.getTlsKeyFile();
        String keyPassword = loadKeyPassword(cfg);

        if (isBlank(certPath) || isBlank(keyPath)) {
            throw new IllegalStateException("TLS 入站缺少证书或私钥路径");
        }

        File certFile = new File(certPath);
        File keyFile = new File(keyPath);
        if (!certFile.isFile() || !keyFile.isFile()) {
            throw new IllegalStateException("TLS 入站证书或私钥文件不可读");
        }

        return buildServerContext(certFile, keyFile, keyPassword);
    }

    private SslContext buildServerContext(File certFile, File keyFile, String keyPassword) {
        try {
            SslContextBuilder builder;
            if (isBlank(keyPassword)) {
                builder = SslContextBuilder.forServer(certFile, keyFile);
            } else {
                builder = SslContextBuilder.forServer(certFile, keyFile, keyPassword);
            }
            return builder
                    .protocols("TLSv1.3", "TLSv1.2")
                    .ciphers(null, SupportedCipherSuiteFilter.INSTANCE)
                    .build();
        } catch (SSLException e) {
            throw new IllegalStateException("无法加载 TLS 入站证书", e);
        }
    }

    private String loadKeyPassword(ProxyWorkerConfig cfg) {
        if (!isBlank(cfg.getTlsKeyPasswordFile())) {
            try {
                return Files.readString(Path.of(cfg.getTlsKeyPasswordFile()), StandardCharsets.UTF_8).trim();
            } catch (Exception e) {
                throw new IllegalStateException("无法读取 TLS 私钥口令文件", e);
            }
        }
        return cfg.getTlsKeyPassword();
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
