package org.congcong.proxyworker.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.congcong.common.dto.AggregateConfigResponse;
import org.congcong.proxyworker.config.ProxyWorkerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * HTTP客户端管理器
 * 负责与控制管理端进行HTTP通信，支持ETag缓存机制
 */
public class HttpClientManager {
    private static final Logger log = LoggerFactory.getLogger(HttpClientManager.class);
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ProxyWorkerConfig config;
    
    // 缓存相关
    private String lastETag;
    private AggregateConfigResponse cachedConfig;
    
    public HttpClientManager() {
        this.config = ProxyWorkerConfig.getInstance();
        
        // 创建HTTP客户端
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(5000))
                .build();
        
        // 配置JSON序列化器
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        
        log.info("HTTP客户端管理器初始化完成");
    }
    
    /**
     * 获取聚合配置，支持304缓存机制
     * 
     * @return 配置响应结果
     */
    public ConfigFetchResult fetchAggregateConfig() {
        try {
            String url = config.getAggregateConfigUrl();
            log.debug("请求聚合配置: {}", url);
            
            // 构建HTTP请求
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(10000))
                    .GET();
            
            // 如果有缓存的ETag，添加If-None-Match头
            if (lastETag != null) {
                requestBuilder.header("If-None-Match", lastETag);
                log.debug("添加If-None-Match头: {}", lastETag);
            }
            
            HttpRequest request = requestBuilder.build();
            
            // 发送请求
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            log.debug("收到响应，状态码: {}", response.statusCode());
            
            // 处理响应
            switch (response.statusCode()) {
                case 200:
                    // 配置已更新
                    return handleSuccessResponse(response);
                    
                case 304:
                    // 配置未变更，使用缓存
                    log.debug("配置未变更，使用缓存配置");
                    return new ConfigFetchResult(true, cachedConfig, false);
                    
                default:
                    log.error("获取配置失败，状态码: {}, 响应: {}", response.statusCode(), response.body());
                    return new ConfigFetchResult(false, null, false);
            }
            
        } catch (Exception e) {
            log.error("获取聚合配置时发生异常", e);
            return new ConfigFetchResult(false, null, false);
        }
    }
    
    private ConfigFetchResult handleSuccessResponse(HttpResponse<String> response) {
        try {
            // 解析JSON响应
            AggregateConfigResponse config = objectMapper.readValue(response.body(), AggregateConfigResponse.class);
            
            // 更新缓存
            this.cachedConfig = config;
            
            // 更新ETag
            Optional<String> etagHeader = response.headers().firstValue("ETag");
            if (etagHeader.isPresent()) {
                this.lastETag = etagHeader.get();
                log.debug("更新ETag缓存: {}", lastETag);
            }
            
            log.info("成功获取聚合配置，版本: {}, 生成时间: {}", 
                    config.getVersion(), config.getGeneratedAt());
            
            return new ConfigFetchResult(true, config, true);
            
        } catch (IOException e) {
            log.error("解析配置响应失败", e);
            return new ConfigFetchResult(false, null, false);
        }
    }
    
    /**
     * 获取当前缓存的配置
     */
    public AggregateConfigResponse getCachedConfig() {
        return cachedConfig;
    }
    
    /**
     * 检查是否有缓存的配置
     */
    public boolean hasCachedConfig() {
        return cachedConfig != null;
    }
    
    /**
     * 清除缓存
     */
    public void clearCache() {
        this.lastETag = null;
        this.cachedConfig = null;
        log.debug("已清除配置缓存");
    }
    
    /**
     * 配置获取结果
     */
    public static class ConfigFetchResult {
        private final boolean success;
        private final AggregateConfigResponse config;
        private final boolean updated;
        
        public ConfigFetchResult(boolean success, AggregateConfigResponse config, boolean updated) {
            this.success = success;
            this.config = config;
            this.updated = updated;
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public AggregateConfigResponse getConfig() {
            return config;
        }
        
        public boolean isUpdated() {
            return updated;
        }
    }
}