package org.congcong.proxyworker.server.netty.tls;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import lombok.extern.slf4j.Slf4j;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.ProxyWorkerConfig;

import javax.net.ssl.SSLException;
import java.io.File;
import java.util.Objects;
import java.security.cert.CertificateException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理入站端的 TLS 上下文。
 * 默认使用自签证书生成 Server SslContext，并按入站配置 ID 做缓存。
 * 后续可扩展支持外部证书加载与 mTLS。
 */
@Slf4j
public class TlsContextManager {

    private static final TlsContextManager INSTANCE = new TlsContextManager();
    private final Map<Long, SslContext> serverContexts = new ConcurrentHashMap<>();

    private TlsContextManager() {}

    public static TlsContextManager getInstance() {
        return INSTANCE;
    }

    /**
     * 获取（或创建）入站 Server 端 SslContext。
     * 当未配置外部证书时，使用自签证书生成。
     */
    public SslContext getServerContext(InboundConfig inboundConfig) {
        Long id = inboundConfig.getId() == null ? -1L : inboundConfig.getId();
        return serverContexts.computeIfAbsent(id, k -> createServerContextWithFallback());
    }

    /**
     * 优先尝试加载外部证书，如果不可用则回退到自签证书。
     */
    private SslContext createServerContextWithFallback() {
        SslContext external = tryCreateExternalContext();
        if (external != null) {
            log.info("External SSL Context created");
            return external;
        }
        log.warn("Self SSL Context created");
        return createSelfSignedContext();
    }

    /**
     * 从配置中读取证书与私钥路径，尝试创建 SslContext。
     * 仅当证书链文件与私钥文件同时存在且可读时返回非空；否则返回 null。
     */
    private SslContext tryCreateExternalContext() {
        ProxyWorkerConfig cfg = ProxyWorkerConfig.getInstance();
        String certPath = cfg.getTlsCertFile();
        String keyPath = cfg.getTlsKeyFile();
        String keyPassword = cfg.getTlsKeyPassword();

        if (isBlank(certPath) || isBlank(keyPath)) {
            return null;
        }

        File certFile = new File(certPath);
        File keyFile = new File(keyPath);
        if (!certFile.isFile() || !keyFile.isFile()) {
            return null;
        }

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
            // 加载失败时回退到自签逻辑，由上层处理
            return null;
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private SslContext createSelfSignedContext() {
        try {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            return SslContextBuilder
                    .forServer(ssc.certificate(), ssc.privateKey())
                    // 优先启用较新协议，允许根据运行环境协商
                    .protocols("TLSv1.3", "TLSv1.2")
                    // 默认 cipher 过滤器，后续可按需白名单
                    .ciphers(null, SupportedCipherSuiteFilter.INSTANCE)
                    .build();
        } catch (CertificateException | SSLException e) {
            throw new RuntimeException("Failed to create self-signed server SslContext", e);
        }
    }
}