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
    /**
     * 统计区间内总流量（bytes_in + bytes_out）。
     */
    long sumBytes(java.time.LocalDateTime from, java.time.LocalDateTime to);
    /**
     * 统计指定入站在时间区间内的上下行流量。
     */
    InboundTrafficDTO getInboundTraffic(Long inboundId, java.time.LocalDateTime from, java.time.LocalDateTime toExclusive);
}
