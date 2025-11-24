package org.congcong.controlmanager.logstore;

import org.congcong.common.dto.AccessLog;
import org.congcong.controlmanager.dto.*;

import java.util.List;
import java.util.Optional;

public interface AccessLogStore {
    int ingest(List<AccessLog> logs);
    PageResponse<AccessLogListItem> queryAccessLogs(AccessLogQueryRequest req);
    Optional<AccessLogDetail> getAccessLogDetail(String id);
    List<TopItem> aggregateDailyTopRange(String from, String to, String dimension, String metric, int limit, Long userId);
    List<TimeSeriesPoint> getGlobalTrafficTrend(java.time.LocalDateTime from, java.time.LocalDateTime to);
    List<TimeSeriesPoint> getUserTrafficTrend(Long userId, java.time.LocalDateTime from, java.time.LocalDateTime to);
    int cleanupExpiredMinuteTrafficStats();
    long countExpiredMinuteTrafficStats();
}