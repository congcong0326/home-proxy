package org.congcong.controlmanager.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.congcong.controlmanager.dto.UserTrafficStatsDTO;
import org.congcong.controlmanager.service.UserTrafficStatsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 用户流量统计控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/user-traffic-stats")
@RequiredArgsConstructor
public class UserTrafficStatsController {

    private final UserTrafficStatsService userTrafficStatsService;

    /**
     * 获取用户当天流量统计
     * GET /api/user-traffic-stats/daily
     * 
     * @param date 查询日期，格式：yyyy-MM-dd，不传则默认为当天
     * @return 用户当天流量统计列表
     */
    @GetMapping("/daily")
    public ResponseEntity<List<UserTrafficStatsDTO>> getDailyUserTrafficStats(
            @RequestParam(required = false) 
            @DateTimeFormat(pattern = "yyyy-MM-dd") 
            LocalDate date) {
        
        log.info("查询用户当天流量统计，日期: {}", date != null ? date : "今天");
        
        List<UserTrafficStatsDTO> stats = userTrafficStatsService.getDailyUserTrafficStats(date);
        
        return ResponseEntity.ok(stats);
    }

    /**
     * 获取用户本月流量统计
     * GET /api/user-traffic-stats/monthly
     * 
     * @param yearMonth 查询年月，格式：yyyy-MM，不传则默认为当月
     * @return 用户本月流量统计列表
     */
    @GetMapping("/monthly")
    public ResponseEntity<List<UserTrafficStatsDTO>> getMonthlyUserTrafficStats(
            @RequestParam(required = false) 
            @DateTimeFormat(pattern = "yyyy-MM") 
            YearMonth yearMonth) {
        
        log.info("查询用户本月流量统计，年月: {}", yearMonth != null ? yearMonth : "本月");
        
        List<UserTrafficStatsDTO> stats = userTrafficStatsService.getMonthlyUserTrafficStats(yearMonth);
        
        return ResponseEntity.ok(stats);
    }
}