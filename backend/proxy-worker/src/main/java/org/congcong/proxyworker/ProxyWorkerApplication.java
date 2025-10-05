package org.congcong.proxyworker;

import org.congcong.common.dto.AggregateConfigResponse;
import org.congcong.proxyworker.service.AggregateConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 代理工作节点主应用程序
 * 演示如何使用聚合配置服务
 */
public class ProxyWorkerApplication {
    private static final Logger log = LoggerFactory.getLogger(ProxyWorkerApplication.class);
    
    public static void main(String[] args) {
        log.info("启动代理工作节点应用程序");
        
        // 创建配置服务
        AggregateConfigService configService = new AggregateConfigService();
        
        // 设置配置变更监听器
        configService.setConfigChangeListener(new ConfigChangeListener());
        
        // 启动配置服务
        configService.start();
        
        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("应用程序正在关闭...");
            configService.stop();
        }));
        
        // 主线程保持运行
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            log.info("应用程序被中断");
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 配置变更监听器实现
     */
    private static class ConfigChangeListener implements AggregateConfigService.ConfigChangeListener {
        @Override
        public void onConfigChanged(AggregateConfigResponse newConfig) {
            log.info("收到配置变更通知:");
            log.info("  版本: {}", newConfig.getVersion());
            log.info("  生成时间: {}", newConfig.getGeneratedAt());
            log.info("  入站配置数量: {}", newConfig.getInbounds() != null ? newConfig.getInbounds().size() : 0);
            log.info("  路由配置数量: {}", newConfig.getRoutes() != null ? newConfig.getRoutes().size() : 0);
            log.info("  限流配置数量: {}", newConfig.getRateLimits() != null ? newConfig.getRateLimits().size() : 0);
            log.info("  用户配置数量: {}", newConfig.getUsers() != null ? newConfig.getUsers().size() : 0);
            log.info("  配置哈希: {}", newConfig.getConfigHash());
            
            // 在这里可以添加配置应用逻辑
            // 例如：更新代理规则、重新配置路由等
        }
    }
}