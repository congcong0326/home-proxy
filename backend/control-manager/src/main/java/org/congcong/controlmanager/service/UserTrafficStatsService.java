package org.congcong.controlmanager.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.congcong.controlmanager.dto.UserTrafficStatsDTO;
import org.congcong.controlmanager.repository.agg.MinuteTrafficStatsRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 用户流量统计服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserTrafficStatsService {

    private final MinuteTrafficStatsRepository minuteTrafficStatsRepository;

    /**
     * 获取用户当天流量统计
     * 
     * @param date 指定日期，如果为null则使用当前日期
     * @return 用户流量统计列表，按总流量降序排列
     */
    public List<UserTrafficStatsDTO> getDailyUserTrafficStats(LocalDate date) {
        if (date == null) {
            date = LocalDate.now();
        }
        
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        
        log.info("查询用户当天流量统计: {} - {}", dayStart, dayEnd);
        
        List<UserTrafficStatsDTO> stats = minuteTrafficStatsRepository
            .findDailyUserTrafficStats(dayStart, dayEnd);
        
        // 设置统计时间段
        String period = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        stats.forEach(stat -> stat.setPeriod(period));
        
        log.info("查询到 {} 个用户的当天流量统计", stats.size());
        return stats;
    }

    /**
     * 获取用户本月流量统计
     * 
     * @param yearMonth 指定年月，如果为null则使用当前年月
     * @return 用户流量统计列表，按总流量降序排列
     */
    public List<UserTrafficStatsDTO> getMonthlyUserTrafficStats(YearMonth yearMonth) {
        if (yearMonth == null) {
            yearMonth = YearMonth.now();
        }
        
        LocalDateTime monthStart = yearMonth.atDay(1).atStartOfDay();
        LocalDateTime monthEnd = yearMonth.plusMonths(1).atDay(1).atStartOfDay();
        
        log.info("查询用户本月流量统计: {} - {}", monthStart, monthEnd);
        
        List<UserTrafficStatsDTO> stats = minuteTrafficStatsRepository
            .findMonthlyUserTrafficStats(monthStart, monthEnd);
        
        // 设置统计时间段
        String period = yearMonth.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        stats.forEach(stat -> stat.setPeriod(period));
        
        log.info("查询到 {} 个用户的本月流量统计", stats.size());
        return stats;
    }
}