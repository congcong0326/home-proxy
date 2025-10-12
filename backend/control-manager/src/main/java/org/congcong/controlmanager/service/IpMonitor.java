package org.congcong.controlmanager.service;

import lombok.extern.slf4j.Slf4j;
import org.congcong.controlmanager.config.WolConfig;
import org.congcong.controlmanager.entity.PcStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class IpMonitor {

    @Autowired
    private WolConfigService wolConfigService;

    private final Map<String, Boolean> statusMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean isStarted = false;

    /**
     * 启动IP监控服务
     */
    public synchronized void start() {
        if (isStarted) {
            log.warn("IP监控服务已经启动");
            return;
        }
        
        log.info("启动IP监控服务");
        // 每5秒执行一次检测任务
        scheduler.scheduleAtFixedRate(this::monitorIps, 0, 5, TimeUnit.SECONDS);
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
     * 刷新配置（当WOL配置发生变化时调用）
     */
    public void refreshConfigs() {
        log.info("刷新WOL配置");
        // 清理已删除设备的状态
        List<WolConfig> enabledConfigs = wolConfigService.getAllEnabledConfigs();
        List<String> currentIps = enabledConfigs.stream()
                .map(WolConfig::getIpAddress)
                .toList();
        
        // 移除不再监控的IP状态
        statusMap.keySet().removeIf(ip -> !currentIps.contains(ip));
    }

    /**
     * 监控IP状态的核心方法
     */
    private void monitorIps() {
        try {
            List<WolConfig> enabledConfigs = wolConfigService.getAllEnabledConfigs();
            
            for (WolConfig config : enabledConfigs) {
                String ip = config.getIpAddress();
                try {
                    boolean isOnline = InetAddress.getByName(ip).isReachable(1000); // 1秒超时
                    statusMap.put(ip, isOnline);
                    
                    if (log.isDebugEnabled()) {
                        log.debug("设备 {} ({}) 状态: {}", config.getName(), ip, isOnline ? "在线" : "离线");
                    }
                } catch (Exception e) {
                    statusMap.put(ip, false); // 异常视为离线
                    log.warn("检测设备 {} ({}) 状态时发生异常: {}", config.getName(), ip, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("监控IP状态时发生异常", e);
        }
    }

    /**
     * 根据IP地址获取WOL配置
     */
    public WolConfig getByIp(String ip) {
        return wolConfigService.getConfigByIpAddress(ip).orElse(null);
    }

    /**
     * 获取所有PC状态
     */
    public List<PcStatus> getAllPcStatus() {
        List<PcStatus> result = new ArrayList<>();
        List<WolConfig> allConfigs = wolConfigService.getAllConfigs();
        
        for (WolConfig config : allConfigs) {
            String ipAddress = config.getIpAddress();
            Boolean online = isOnline(ipAddress);
            
            PcStatus pcStatus = new PcStatus();
            pcStatus.setName(config.getName());
            pcStatus.setOnline(online);
            pcStatus.setIp(config.getIpAddress());
            pcStatus.setEnabled(config.isEnabled());
            pcStatus.setMacAddress(config.getMacAddress());
            pcStatus.setWolPort(config.getWolPort());
            pcStatus.setNotes(config.getNotes());
            
            result.add(pcStatus);
        }
        return result;
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
