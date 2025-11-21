package org.congcong.proxyworker.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 代理工作节点配置类
 * 负责读取配置文件中的管理端地址信息
 */
public class ProxyWorkerConfig {
    private static final Logger log = LoggerFactory.getLogger(ProxyWorkerConfig.class);
    
    private static final String CONFIG_FILE = "proxy-worker.properties";
    private static final String DEFAULT_CONTROL_HOST = "localhost";
    private static final int DEFAULT_CONTROL_PORT = 8080;
    
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
        
        this.controlHost = props.getProperty("control.host", DEFAULT_CONTROL_HOST);
        this.controlPort = Integer.parseInt(props.getProperty("control.port", String.valueOf(DEFAULT_CONTROL_PORT)));
        this.controlBaseUrl = String.format("http://%s:%d", controlHost, controlPort);
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
        
        return props;
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