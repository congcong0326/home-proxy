package org.congcong.proxyworker.service;

import org.congcong.common.dto.AggregateConfigResponse;
import org.congcong.proxyworker.http.HttpClientManager;
import org.congcong.proxyworker.config.ProxyWorkerConfig;
import org.congcong.proxyworker.server.ConfigApplyResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聚合配置服务
 * 负责定期从控制管理端获取配置并缓存
 */
public class AggregateConfigService {
    private static final Logger log = LoggerFactory.getLogger(AggregateConfigService.class);
    
    private final HttpClientManager httpClientManager;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    
    // 配置获取间隔（秒）
    private static final int DEFAULT_FETCH_INTERVAL = 30;
    
    // 配置变更监听器
    private ConfigChangeListener configChangeListener;
    private final LastKnownGoodConfigStore lastKnownGoodConfigStore;
    private volatile WorkerControlService.ConfigApplicationStatus applicationStatus = WorkerControlService.ConfigApplicationStatus.empty();
    private volatile String fetchedConfigHash;
    
    public AggregateConfigService() {
        this.httpClientManager = new HttpClientManager();
        this.lastKnownGoodConfigStore = new LastKnownGoodConfigStore(ProxyWorkerConfig.getInstance().getLastKnownGoodConfig());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "config-fetcher");
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * 启动配置服务
     */
    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            log.info("启动聚合配置服务");
            applyLastKnownGoodConfig();
            
            // 立即获取一次配置
            fetchConfigNow();
            
            // 定期获取配置
            scheduler.scheduleWithFixedDelay(
                    this::fetchConfigNow,
                    DEFAULT_FETCH_INTERVAL,
                    DEFAULT_FETCH_INTERVAL,
                    TimeUnit.SECONDS
            );
            
            log.info("聚合配置服务已启动，获取间隔: {}秒", DEFAULT_FETCH_INTERVAL);
        }
    }
    
    /**
     * 停止配置服务
     */
    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            log.info("停止聚合配置服务");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("聚合配置服务已停止");
        }
    }
    
    /**
     * 立即获取配置
     */
    public void fetchConfigNow() {
        try {
            log.debug("开始获取聚合配置");
            
            HttpClientManager.ConfigFetchResult result = httpClientManager.fetchAggregateConfig();
            
            if (result.isSuccess()) {
                if (result.getConfig() != null) {
                    fetchedConfigHash = result.getConfig().getConfigHash();
                }
                if (result.isUpdated()) {
                    log.info("配置已更新，版本: {}", result.getConfig().getVersion());
                    
                    // 通知配置变更监听器
                    if (configChangeListener != null) {
                        try {
                            ConfigApplyResult applyResult = configChangeListener.onConfigChanged(result.getConfig());
                            if (!applyResult.success()) {
                                applicationStatus = new WorkerControlService.ConfigApplicationStatus(null, false,
                                        applyResult.errorSummary(), null);
                                httpClientManager.clearCache();
                                return;
                            }
                            applicationStatus = new WorkerControlService.ConfigApplicationStatus(result.getConfig().getConfigHash(), true,
                                    null, java.time.LocalDateTime.now());
                            lastKnownGoodConfigStore.write(result.getConfig());
                        } catch (Exception e) {
                            applicationStatus = new WorkerControlService.ConfigApplicationStatus(null, false,
                                    summarize(e), null);
                            httpClientManager.clearCache();
                            log.error("通知配置变更监听器时发生异常", e);
                        }
                    }
                } else {
                    log.debug("配置未变更，使用缓存");
                }
            } else {
                log.warn("获取配置失败");
            }
            
        } catch (Exception e) {
            log.error("获取配置时发生异常", e);
        }
    }
    
    /**
     * 获取当前缓存的配置
     */
    public AggregateConfigResponse getCurrentConfig() {
        return httpClientManager.getCachedConfig();
    }
    
    /**
     * 检查是否有缓存的配置
     */
    public boolean hasConfig() {
        return httpClientManager.hasCachedConfig();
    }
    
    /**
     * 清除配置缓存
     */
    public void clearCache() {
        httpClientManager.clearCache();
        log.info("已清除配置缓存");
    }
    
    /**
     * 设置配置变更监听器
     */
    public void setConfigChangeListener(ConfigChangeListener listener) {
        this.configChangeListener = listener;
    }

    public WorkerControlService.ConfigApplicationStatus getApplicationStatus() { return applicationStatus; }

    public String getFetchedConfigHash() { return fetchedConfigHash; }

    private void applyLastKnownGoodConfig() {
        if (configChangeListener == null) return;
        lastKnownGoodConfigStore.read().ifPresent(config -> {
            if (!ProxyWorkerConfig.getInstance().getWorkerId().equals(config.getWorkerId())) return;
            try {
                ConfigApplyResult result = configChangeListener.onConfigChanged(config);
                if (result.success()) {
                    applicationStatus = new WorkerControlService.ConfigApplicationStatus(config.getConfigHash(), true, null,
                            java.time.LocalDateTime.now());
                    log.info("已从最后成功配置恢复 {} 个入站", config.getInbounds() == null ? 0 : config.getInbounds().size());
                } else {
                    applicationStatus = new WorkerControlService.ConfigApplicationStatus(null, false, result.errorSummary(), null);
                }
            } catch (Exception e) {
                applicationStatus = new WorkerControlService.ConfigApplicationStatus(null, false, summarize(e), null);
                log.warn("最后成功配置应用失败: {}", e.toString());
            }
        });
    }

    private String summarize(Exception error) {
        String text = error.getClass().getSimpleName() + ": " + error.getMessage();
        return text.substring(0, Math.min(text.length(), 1024));
    }
    
    /**
     * 配置变更监听器接口
     */
    public interface ConfigChangeListener {
        /**
         * 配置变更时调用
         * 
         * @param newConfig 新的配置
         */
        ConfigApplyResult onConfigChanged(AggregateConfigResponse newConfig);
    }
    
    /**
     * 获取服务运行状态
     */
    public boolean isRunning() {
        return isRunning.get();
    }
}
