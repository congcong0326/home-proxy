package org.congcong.controlmanager.service;

import lombok.extern.slf4j.Slf4j;
import org.congcong.controlmanager.dto.UserTrafficStatsDTO;
import org.congcong.controlmanager.telemetry.TelemetryJdbcClient;
import org.congcong.controlmanager.telemetry.TelemetrySchema;
import org.congcong.controlmanager.telemetry.TelemetryStore;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用户流量统计服务：按已结束会话（遥测会话表）的结束时间入账，上传为 byteIn、下载为 byteOut。
 */
@Slf4j
@Service
public class UserTrafficStatsService {

    private static final String SQL = "SELECT user_id, MAX(username) AS username, SUM(upload_bytes) AS byte_in, "
            + "SUM(download_bytes) AS byte_out FROM " + TelemetrySchema.SESSIONS.table
            + " WHERE worker_id = ? AND occurred_at >= ? AND occurred_at < ? "
            + "GROUP BY user_id ORDER BY (byte_in + byte_out) DESC";

    private final TelemetryJdbcClient client;
    private final TelemetryStore store;

    public UserTrafficStatsService(TelemetryJdbcClient client, TelemetryStore store) {
        this.client = client;
        this.store = store;
    }

    /** 获取用户当日流量统计，按总流量倒序 */
    public List<UserTrafficStatsDTO> getDailyUserTrafficStats(String workerId, LocalDate date) {
        if (date == null) {
            date = LocalDate.now();
        }
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        String period = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        store.requireQueryAvailable();
        return mapRows(client.query(SQL, normalizeWorkerId(workerId), toInstant(start), toInstant(end)), period);
    }

    /** 获取用户本月流量统计，按总流量倒序 */
    public List<UserTrafficStatsDTO> getMonthlyUserTrafficStats(String workerId, YearMonth yearMonth) {
        if (yearMonth == null) {
            yearMonth = YearMonth.now();
        }
        LocalDateTime start = yearMonth.atDay(1).atStartOfDay();
        LocalDateTime end = yearMonth.plusMonths(1).atDay(1).atStartOfDay();
        String period = yearMonth.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        store.requireQueryAvailable();
        return mapRows(client.query(SQL, normalizeWorkerId(workerId), toInstant(start), toInstant(end)), period);
    }

    private String normalizeWorkerId(String workerId) {
        return workerId == null || workerId.isBlank() ? WorkerControlService.DEFAULT_WORKER_ID : workerId;
    }

    private List<UserTrafficStatsDTO> mapRows(List<Map<String, Object>> rows, String period) {
        List<UserTrafficStatsDTO> list = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Long userId = toLong(r.get("user_id"));
            String username = toStr(r.get("username"));
            Long byteIn = toLong(r.get("byte_in"));
            Long byteOut = toLong(r.get("byte_out"));
            list.add(new UserTrafficStatsDTO(userId, username, byteIn, byteOut, period));
        }
        return list;
    }

    private java.time.Instant toInstant(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toInstant();
    }

    private Long toLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private String toStr(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
