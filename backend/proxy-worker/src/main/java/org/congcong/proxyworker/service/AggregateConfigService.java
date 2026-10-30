package org.congcong.proxyworker.service;

import org.congcong.common.dto.AggregateConfigResponse;
import org.congcong.proxyworker.config.ProxyWorkerConfig;
import org.congcong.proxyworker.server.ConfigApplyResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聚合配置服务
 * 缓存并应用控制端经 WSS 管理通道下发的配置
 */
public class AggregateConfigService {
    private static final Logger log = LoggerFactory.getLogger(AggregateConfigService.class);
    
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    
    // 配置变更监听器
    private ConfigChangeListener configChangeListener;
    private final LastKnownGoodConfigStore lastKnownGoodConfigStore;
    private volatile ConfigApplicationStatus applicationStatus = ConfigApplicationStatus.empty();
    private volatile String fetchedConfigHash;
    private volatile AggregateConfigResponse pushedConfig;
    
    public AggregateConfigService() {
        ProxyWorkerConfig config = ProxyWorkerConfig.getInstance();
        this.lastKnownGoodConfigStore = new LastKnownGoodConfigStore(config.getLastKnownGoodConfig());
    }
    
    /**
     * Starts only the local part of configuration handling. A connected
     * controller will answer management-channel polls with configurations.
     */
    public void startManaged() {
        if (isRunning.compareAndSet(false, true)) {
            log.info("启动 WebSocket 管理通道配置服务");
            applyLastKnownGoodConfig();
        }
    }
    
    /**
     * 停止配置服务
     */
    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            log.info("聚合配置服务已停止");
        }
    }

    /**
     * 获取当前缓存的配置
     */
    public AggregateConfigResponse getCurrentConfig() {
        return pushedConfig;
    }
    
    /**
     * 检查是否有缓存的配置
     */
    public boolean hasConfig() {
        return pushedConfig != null;
    }
    
    /**
     * 设置配置变更监听器
     */
    public void setConfigChangeListener(ConfigChangeListener listener) {
        this.configChangeListener = listener;
    }

    public ConfigApplicationStatus getApplicationStatus() {
        return applicationStatus;
    }

    public String getFetchedConfigHash() {
        return fetchedConfigHash;
    }

    /** Hash advertised to the controller. Failed applies deliberately advertise no hash so they retry. */
    public String getManagedPollingConfigHash() {
        return Boolean.TRUE.equals(applicationStatus.success()) ? applicationStatus.appliedConfigHash() : null;
    }

    /** Applies a configuration received over the worker management WebSocket. */
    public void acceptManagedConfig(AggregateConfigResponse config) {
        if (config == null || !ProxyWorkerConfig.getInstance().getWorkerId().equals(config.getWorkerId())) {
            log.warn("忽略来自管理通道的不匹配配置");
            return;
        }
        if (config.getConfigHash() != null && config.getConfigHash().equals(fetchedConfigHash)
                && Boolean.TRUE.equals(applicationStatus.success())) {
            return;
        }
        acceptConfig(config);
    }

    private void acceptConfig(AggregateConfigResponse config) {
        if (config == null) {
            log.warn("控制端返回空配置");
            return;
        }
        fetchedConfigHash = config.getConfigHash();
        pushedConfig = config;
        log.info("配置已更新，版本: {}", config.getVersion());
        if (configChangeListener == null) {
            return;
        }
        try {
            ConfigApplyResult applyResult = configChangeListener.onConfigChanged(config);
            if (!applyResult.success()) {
                applicationStatus = new ConfigApplicationStatus(null, false,
                        applyResult.errorSummary(), null);
                return;
            }
            applicationStatus = new ConfigApplicationStatus(config.getConfigHash(), true,
                    null, java.time.LocalDateTime.now());
            lastKnownGoodConfigStore.write(config);
        } catch (Exception e) {
            applicationStatus = new ConfigApplicationStatus(null, false,
                    summarize(e), null);
            log.error("通知配置变更监听器时发生异常", e);
        }
    }

    private void applyLastKnownGoodConfig() {
        if (configChangeListener == null) return;
        lastKnownGoodConfigStore.read().ifPresent(config -> {
            if (!ProxyWorkerConfig.getInstance().getWorkerId().equals(config.getWorkerId())) return;
            try {
                ConfigApplyResult result = configChangeListener.onConfigChanged(config);
                if (result.success()) {
                    // The recovered snapshot is the worker's fetched configuration too.
                    // Keep this hash populated so the first management heartbeat after
                    // startup reports a complete configuration state even when the
                    // controller answers CONFIG_UNCHANGED.
                    fetchedConfigHash = config.getConfigHash();
                    pushedConfig = config;
                    applicationStatus = new ConfigApplicationStatus(config.getConfigHash(), true, null,
                            java.time.LocalDateTime.now());
                    log.info("已从最后成功配置恢复 {} 个入站", config.getInbounds() == null ? 0 : config.getInbounds().size());
                } else {
                    applicationStatus = new ConfigApplicationStatus(null, false, result.errorSummary(), null);
                }
            } catch (Exception e) {
                applicationStatus = new ConfigApplicationStatus(null, false, summarize(e), null);
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
