package org.congcong.controlmanager.service;

import lombok.extern.slf4j.Slf4j;
import org.congcong.controlmanager.config.WolConfig;
import org.congcong.controlmanager.entity.PcStatus;
import org.congcong.controlmanager.event.WolConfigChangedEvent;
import org.congcong.controlmanager.repository.WolConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class IpMonitor {

    private final WolConfigRepository wolConfigRepository;

    private final Map<String, Boolean> statusMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean isStarted = false;
    
    // 缓存当前启用的配置，避免每次都查数据库
    private volatile List<WolConfig> cachedEnabledConfigs = new ArrayList<>();

    public IpMonitor(WolConfigRepository wolConfigRepository) {
        this.wolConfigRepository = wolConfigRepository;
    }

    /**
     * 启动IP监控服务
     */
    public synchronized void start() {
        if (isStarted) {
            log.warn("IP监控服务已经启动");
            return;
        }
        
        log.info("启动IP监控服务");
        // 初始加载配置
        refreshConfigs();
        // 每30秒执行一次检测任务
        scheduler.scheduleAtFixedRate(this::monitorIps, 0, 30, TimeUnit.SECONDS);
        isStarted = true;
    }

    /**
     * 停止IP监控服务
     */
    public synchronized void stop() {
        if (!isStarted) {
            return;
        }
        
        log.info("停止IP监控服务");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        isStarted = false;
    }

    /**
     * 监听WOL配置变更事件（在事务提交后处理）
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWolConfigChanged(WolConfigChangedEvent event) {
        log.info("收到WOL配置变更事件: {} - {}", event.getChangeType(), event.getConfigName());
        refreshConfigs();
    }
    
    /**
     * 手动刷新配置
     */
    public void refreshConfigs() {
        log.info("刷新WOL配置");
        try {
            // 重新加载启用的配置
            cachedEnabledConfigs = wolConfigRepository.findAllEnabled();
            log.info("已加载 {} 个启用的WOL配置", cachedEnabledConfigs.size());
            
            // 清理不再监控的IP状态
            Set<String> currentIps = cachedEnabledConfigs.stream()
                    .map(WolConfig::getIpAddress)
                    .collect(Collectors.toSet());
            
            statusMap.entrySet().removeIf(entry -> !currentIps.contains(entry.getKey()));
            
        } catch (Exception e) {
            log.error("刷新WOL配置时发生错误", e);
        }
    }

    /**
     * 监控IP状态的核心方法
     */
    private void monitorIps() {
        try {
            // 使用缓存的配置，避免每次都查数据库
            for (WolConfig config : cachedEnabledConfigs) {
                String ip = config.getIpAddress();
                Boolean currentStatus = isOnline(ip);
                statusMap.put(ip, currentStatus);
            }
        } catch (Exception e) {
            log.error("监控IP状态时发生错误", e);
        }
    }

    /**
     * 根据IP地址获取WOL配置
     */
    public WolConfig getByIp(String ip) {
        return wolConfigRepository.findByIpAddress(ip).orElse(null);
    }

    /**
     * 获取所有PC状态
     */
    public List<PcStatus> getAllPcStatus() {
        List<PcStatus> pcStatusList = new ArrayList<>();
        List<WolConfig> allConfigs = wolConfigRepository.findAll();
        
        for (WolConfig config : allConfigs) {
            String ip = config.getIpAddress();
            Boolean isOnline = statusMap.get(ip);
            
            PcStatus pcStatus = new PcStatus();
            pcStatus.setName(config.getName());
            pcStatus.setIp(ip);
            pcStatus.setMacAddress(config.getMacAddress());
            pcStatus.setWolPort(config.getWolPort());
            pcStatus.setEnabled(config.isEnabled());
            pcStatus.setNotes(config.getNotes());
            pcStatus.setOnline(isOnline != null ? isOnline : false);
            
            pcStatusList.add(pcStatus);
        }
        
        return pcStatusList;
    }

    /**
     * 检查指定IP是否在线
     */
    private Boolean isOnline(String ip) {
        Boolean status = statusMap.get(ip);
        return status != null ? status : false;
    }

    /**
     * 获取指定IP的在线状态
     */
    public Boolean getIpStatus(String ip) {
        return isOnline(ip);
    }

    /**
     * 手动检测指定IP的状态
     */
    public boolean checkIpStatus(String ip) {
        try {
            boolean isOnline = InetAddress.getByName(ip).isReachable(2000); // 2秒超时
            statusMap.put(ip, isOnline);
            return isOnline;
        } catch (Exception e) {
            log.warn("手动检测IP {} 状态时发生异常: {}", ip, e.getMessage());
            statusMap.put(ip, false);
            return false;
        }
    }
}
