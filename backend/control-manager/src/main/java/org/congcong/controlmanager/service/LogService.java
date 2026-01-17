package org.congcong.controlmanager.service;

import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.AccessLog;
import org.congcong.controlmanager.dto.*;
import org.congcong.controlmanager.entity.AccessLogEntity;
import org.congcong.controlmanager.repository.AccessLogRepository;
import org.congcong.controlmanager.repository.AuthLogRepository;
import org.congcong.controlmanager.repository.agg.MonthlyAppStatsRepository;
import org.congcong.controlmanager.repository.agg.MonthlyUserStatsRepository;
import org.congcong.controlmanager.repository.agg.MonthlyUserAppStatsRepository;
import org.congcong.controlmanager.repository.agg.MonthlySrcGeoStatsRepository;
import org.congcong.controlmanager.repository.agg.MonthlyDstGeoStatsRepository;
import org.congcong.controlmanager.repository.agg.DailyUserStatsRepository;
import org.congcong.controlmanager.repository.agg.DailyAppStatsRepository;
import org.congcong.controlmanager.repository.agg.DailyUserAppStatsRepository;
import org.congcong.controlmanager.repository.agg.DailySrcGeoStatsRepository;
import org.congcong.controlmanager.repository.agg.DailyDstGeoStatsRepository;
import org.congcong.controlmanager.repository.agg.MinuteTrafficStatsRepository;
import org.congcong.controlmanager.entity.agg.MinuteTrafficStats;
import org.congcong.controlmanager.entity.agg.MonthlyAppStats;
import org.congcong.controlmanager.entity.agg.MonthlyUserStats;
import org.congcong.controlmanager.entity.agg.MonthlyUserAppStats;
import org.congcong.controlmanager.entity.agg.MonthlySrcGeoStats;
import org.congcong.controlmanager.entity.agg.MonthlyDstGeoStats;
import org.congcong.controlmanager.entity.agg.DailyAppStats;
import org.congcong.controlmanager.entity.agg.DailyUserStats;
import org.congcong.controlmanager.entity.agg.DailyUserAppStats;
import org.congcong.controlmanager.entity.agg.DailySrcGeoStats;
import org.congcong.controlmanager.entity.agg.DailyDstGeoStats;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.congcong.controlmanager.logstore.AccessLogStoreFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
@RequiredArgsConstructor
public class LogService {

    private final AccessLogStoreFactory accessLogStoreFactory;

    @Transactional
    public int saveAccessLogs(List<AccessLog> logs) {
        if (logs == null || logs.isEmpty()) return 0;
        return accessLogStoreFactory.current().ingest(logs);
    }

    /**
     * 分页检索访问日志
     */
    public PageResponse<AccessLogListItem> queryAccessLogs(AccessLogQueryRequest req) {
        return accessLogStoreFactory.current().queryAccessLogs(req);
    }

    /**
     * 基于日度聚合表的 TopN 查询（支持时间区间）
     */
    public List<org.congcong.controlmanager.dto.TopItem> aggregateDailyTopRange(String from, String to, String dimension, String metric, int limit, Long userId) {
        return accessLogStoreFactory.current().aggregateDailyTopRange(from, to, dimension, metric, limit, userId);
    }

    /**
     * 访问日志详情
     */
    public Optional<AccessLogDetail> getAccessLogDetail(String id) {
        return accessLogStoreFactory.current().getAccessLogDetail(id);
    }


    /**
     * 获取全局流量趋势（分钟级）
     */
    public List<TimeSeriesPoint> getGlobalTrafficTrend(LocalDateTime from, LocalDateTime to) {
        return accessLogStoreFactory.current().getGlobalTrafficTrend(from, to);
    }

    /**
     * 获取指定用户的流量趋势（分钟级）
     */
    public List<TimeSeriesPoint> getUserTrafficTrend(Long userId, LocalDateTime from, LocalDateTime to) {
        return accessLogStoreFactory.current().getUserTrafficTrend(userId, from, to);
    }

    /**
     * 清理过期的分钟级流量数据
     */
    @Transactional
    public int cleanupExpiredMinuteTrafficStats() {
        return accessLogStoreFactory.current().cleanupExpiredMinuteTrafficStats();
    }

    /**
     * 统计过期的分钟级流量数据数量
     */
    public long countExpiredMinuteTrafficStats() {
        return accessLogStoreFactory.current().countExpiredMinuteTrafficStats();
    }

    /**
     * 统计指定时间范围的总流量（bytes_in + bytes_out）。
     */
    public long sumBytes(LocalDateTime from, LocalDateTime to) {
        return accessLogStoreFactory.current().sumBytes(from, to);
    }
}
