package org.congcong.proxyworker.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 代理工作节点配置类
 * 负责读取配置文件中的管理端地址信息
 */
public class ProxyWorkerConfig {
    private static final Logger log = LoggerFactory.getLogger(ProxyWorkerConfig.class);
    
    private static final String CONFIG_FILE = "proxy-worker.properties";
    private static final String DEFAULT_CONTROL_HOST = "localhost";
    private static final int DEFAULT_CONTROL_PORT = 8081;
    
    private final String controlHost;
    private final int controlPort;
    private final String controlBaseUrl;
    // TLS certificate configuration (optional)
    private final String tlsCertFile;
    private final String tlsKeyFile;
    private final String tlsKeyPassword;
    
    private static ProxyWorkerConfig instance;
    
    private ProxyWorkerConfig() {
        Properties props = loadProperties();

        String configuredBaseUrl = trimToNull(props.getProperty("control.manager.url"));
        if (configuredBaseUrl != null) {
            URI controlUri = URI.create(trimTrailingSlash(configuredBaseUrl));
            this.controlHost = controlUri.getHost();
            this.controlPort = resolvePort(controlUri);
            this.controlBaseUrl = trimTrailingSlash(configuredBaseUrl);
        } else {
            this.controlHost = props.getProperty("control.host", DEFAULT_CONTROL_HOST);
            this.controlPort = Integer.parseInt(props.getProperty("control.port", String.valueOf(DEFAULT_CONTROL_PORT)));
            this.controlBaseUrl = String.format("http://%s:%d", controlHost, controlPort);
        }
        // TLS certificate paths (optional)
        this.tlsCertFile = props.getProperty("tls.certFile", null);
        this.tlsKeyFile = props.getProperty("tls.keyFile", null);
        this.tlsKeyPassword = props.getProperty("tls.keyPassword", "");
        
        log.info("代理工作节点配置加载完成 - 控制端地址: {}", controlBaseUrl);
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
        overrideProperty(props, "control.manager.url", "CONTROL_MANAGER_URL");
        overrideProperty(props, "control.host", "CONTROL_HOST");
        overrideProperty(props, "control.port", "CONTROL_PORT");
        overrideProperty(props, "tls.certFile", "TLS_CERT_FILE");
        overrideProperty(props, "tls.keyFile", "TLS_KEY_FILE");
        overrideProperty(props, "tls.keyPassword", "TLS_KEY_PASSWORD");
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

    private static String trimTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static int resolvePort(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return 443;
        }
        return 80;
    }
    
    public String getControlHost() {
        return controlHost;
    }
    
    public int getControlPort() {
        return controlPort;
    }
    
    public String getControlBaseUrl() {
        return controlBaseUrl;
    }

    public String getAggregateConfigUrl() {
        return controlBaseUrl + "/api/config/aggregate";
    }

    /**
     * 日志发送端点（管理端提供）
     */
    public String getAccessLogUrl() {
        return controlBaseUrl + "/api/logs/access";
    }

    public String getAuthLogUrl() {
        return controlBaseUrl + "/api/logs/auth";
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
}
