package org.congcong.proxyworker.server.netty.tls;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.congcong.proxyworker.config.InboundConfig;

import javax.net.ssl.SSLException;
import java.security.cert.CertificateException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理入站端的 TLS 上下文。
 * 默认使用自签证书生成 Server SslContext，并按入站配置 ID 做缓存。
 * 后续可扩展支持外部证书加载与 mTLS。
 */
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
        return serverContexts.computeIfAbsent(id, k -> createSelfSignedContext());
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