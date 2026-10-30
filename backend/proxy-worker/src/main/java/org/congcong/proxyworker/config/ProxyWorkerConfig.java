package org.congcong.proxyworker.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 代理工作节点配置类
 * 负责读取 Worker 标识、TLS 证书与 WSS 管理通道配置。
 * 旧版 HTTP 链路的属性（control.*、worker.tokenFile、worker.control.pollIntervalMs、
 * worker.management.enabled 等）不再读取，配置里残留这些条目会被直接忽略。
 */
public class ProxyWorkerConfig {
    private static final Logger log = LoggerFactory.getLogger(ProxyWorkerConfig.class);
    
    private static final String CONFIG_FILE = "proxy-worker.properties";
    
    private final String workerId;
    // TLS certificate configuration (optional)
    private final String tlsCertFile;
    private final String tlsKeyFile;
    private final String tlsKeyPassword;
    private final String tlsKeyPasswordFile;
    private final boolean tlsAllowSelfSigned;
    private final String lastKnownGoodConfig;
    private final int managementPort;
    private final String managementTokenFile;
    private final long managementPollIntervalMs;
    
    private static ProxyWorkerConfig instance;
    
    private ProxyWorkerConfig() {
        Properties props = loadProperties();

        // TLS certificate paths (optional)
        this.tlsCertFile = props.getProperty("tls.certFile", null);
        this.tlsKeyFile = props.getProperty("tls.keyFile", null);
        this.tlsKeyPassword = props.getProperty("tls.keyPassword", "");
        this.tlsKeyPasswordFile = trimToNull(props.getProperty("tls.keyPasswordFile"));
        this.tlsAllowSelfSigned = Boolean.parseBoolean(props.getProperty("tls.allowSelfSigned", "false"));
        this.lastKnownGoodConfig = props.getProperty("worker.lastKnownGoodConfig", "/data/proxy-worker/last-known-good-config.json");
        this.workerId = props.getProperty("worker.id", "default");
        this.managementPort = Integer.parseInt(props.getProperty("worker.management.port", "18444"));
        this.managementTokenFile = props.getProperty("worker.management.tokenFile", "/data/proxy-worker/management.token");
        this.managementPollIntervalMs = Long.parseLong(props.getProperty("worker.management.pollIntervalMs", "2000"));
        
        log.info("代理工作节点配置加载完成 - workerId: {}, 管理端口: {}", workerId, managementPort);
    }
    
    public static synchronized ProxyWorkerConfig getInstance() {
        if (instance == null) {
            instance = new ProxyWorkerConfig();
        }
        return instance;
    }
    
    private Properties loadProperties() {
        Properties props = new Properties();
        loadClasspathProperties(props);
        loadExternalProperties(props);
        applyRuntimeOverrides(props);

        return props;
    }

    private void loadClasspathProperties(Properties props) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (is != null) {
                props.load(is);
                log.info("成功加载配置文件: {}", CONFIG_FILE);
            } else {
                log.warn("配置文件 {} 不存在，使用默认配置", CONFIG_FILE);
            }
        } catch (IOException e) {
            log.error("加载配置文件失败，使用默认配置", e);
        }
    }

    private void loadExternalProperties(Properties props) {
        String externalConfig = firstNonBlank(
                System.getProperty("PROXY_WORKER_CONFIG"),
                System.getProperty("proxy.worker.config"),
                System.getenv("PROXY_WORKER_CONFIG")
        );
        if (externalConfig == null) {
            return;
        }

        Path configPath = Path.of(externalConfig);
        try (InputStream is = Files.newInputStream(configPath)) {
            props.load(is);
            log.info("成功加载外部配置文件: {}", configPath);
        } catch (IOException e) {
            log.error("加载外部配置文件失败: {}，继续使用其他配置来源", configPath, e);
        }
    }

    private void applyRuntimeOverrides(Properties props) {
        overrideProperty(props, "tls.certFile", "TLS_CERT_FILE");
        overrideProperty(props, "tls.keyFile", "TLS_KEY_FILE");
        overrideProperty(props, "tls.keyPassword", "TLS_KEY_PASSWORD");
        overrideProperty(props, "tls.keyPasswordFile", "TLS_KEY_PASSWORD_FILE");
        overrideProperty(props, "tls.allowSelfSigned", "TLS_ALLOW_SELF_SIGNED");
        overrideProperty(props, "worker.id", "WORKER_ID");
        overrideProperty(props, "worker.lastKnownGoodConfig", "LAST_KNOWN_GOOD_CONFIG");
        overrideProperty(props, "worker.management.port", "WORKER_MANAGEMENT_PORT");
        overrideProperty(props, "worker.management.tokenFile", "WORKER_MANAGEMENT_TOKEN_FILE");
        overrideProperty(props, "worker.management.pollIntervalMs", "WORKER_MANAGEMENT_POLL_INTERVAL_MS");
    }

    private void overrideProperty(Properties props, String propertyName, String envName) {
        String overrideValue = firstNonBlank(
                System.getProperty(envName),
                System.getProperty(propertyName),
                System.getenv(envName)
        );
        if (overrideValue != null) {
            props.setProperty(propertyName, overrideValue);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public String getWorkerId() {
        return workerId;
    }

    /**
     * 外部 TLS 证书配置（可选）。
     * 返回值可能为 null 或空字符串，调用方需自行判空验证。
     */
    public String getTlsCertFile() {
        return tlsCertFile;
    }

    public String getTlsKeyFile() {
        return tlsKeyFile;
    }

    public String getTlsKeyPassword() {
        return tlsKeyPassword;
    }

    public String getTlsKeyPasswordFile() {
        return tlsKeyPasswordFile;
    }

    public boolean isTlsAllowSelfSigned() {
        return tlsAllowSelfSigned;
    }

    public String getLastKnownGoodConfig() {
        return lastKnownGoodConfig;
    }

    public int getManagementPort() {
        return managementPort;
    }

    public String getManagementTokenFile() {
        return managementTokenFile;
    }

    public long getManagementPollIntervalMs() {
        return managementPollIntervalMs;
    }
}
