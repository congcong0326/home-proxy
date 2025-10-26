package org.congcong.controlmanager.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.congcong.controlmanager.service.LogService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 分钟级流量统计数据清理定时任务
 * 每天凌晨2点执行，清理超过一个月的数据
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinuteTrafficStatsCleanupTask {

    private final LogService logService;

    /**
     * 清理过期的分钟级流量统计数据
     * 每天凌晨2点执行
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupExpiredMinuteTrafficStats() {
        log.info("开始清理过期的分钟级流量统计数据...");
        
        try {
            // 统计要删除的记录数
            long expiredCount = logService.countExpiredMinuteTrafficStats();
            
            if (expiredCount == 0) {
                log.info("没有需要清理的过期分钟级流量统计数据");
                return;
            }
            
            log.info("发现 {} 条过期的分钟级流量统计数据，开始清理...", expiredCount);
            
            // 执行清理
            int deletedCount = logService.cleanupExpiredMinuteTrafficStats();
            
            log.info("分钟级流量统计数据清理完成，删除了 {} 条记录", deletedCount);
            
        } catch (Exception e) {
            log.error("清理分钟级流量统计数据时发生错误", e);
        }
    }

    /**
     * 每小时执行一次统计检查（可选，用于监控数据量）
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void checkMinuteTrafficStatsSize() {
        try {
            long expiredCount = logService.countExpiredMinuteTrafficStats();
            
            if (expiredCount > 10000) { // 如果过期数据超过1万条，记录警告日志
                log.warn("分钟级流量统计表中有 {} 条过期数据等待清理", expiredCount);
            } else if (expiredCount > 0) {
                log.debug("分钟级流量统计表中有 {} 条过期数据", expiredCount);
            }
            
        } catch (Exception e) {
            log.error("检查分钟级流量统计数据大小时发生错误", e);
        }
    }
}