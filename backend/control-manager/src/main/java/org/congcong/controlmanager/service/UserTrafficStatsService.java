package org.congcong.controlmanager.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.congcong.controlmanager.clickhouse.ClickHouseJdbcClient;
import org.congcong.controlmanager.dto.UserTrafficStatsDTO;
import org.congcong.controlmanager.repository.agg.MinuteTrafficStatsRepository;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用户流量统计服务
 */
@Slf4j
@Service
public class UserTrafficStatsService {

    private final ClickHouseJdbcClient client;

    public UserTrafficStatsService(ClickHouseJdbcClient client) {
        this.client = client;
    }

    /** 获取用户当日流量统计，按总流量倒序 */
    public List<UserTrafficStatsDTO> getDailyUserTrafficStats(LocalDate date) {
        if (date == null) {
            date = LocalDate.now();
        }
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        String period = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String sql = "SELECT user_id, any(username) AS username, sum(bytes_in) AS byte_in, sum(bytes_out) AS byte_out " +
        "FROM default.access_log WHERE ts >= ? AND ts < ? GROUP BY user_id ORDER BY (byte_in + byte_out) DESC";
        return mapRows(client.query(sql, toTs(start), toTs(end)), period);
    }

    /** 获取用户本月流量统计，按总流量倒序 */
    public List<UserTrafficStatsDTO> getMonthlyUserTrafficStats(YearMonth yearMonth) {
        if (yearMonth == null) {
            yearMonth = YearMonth.now();
        }
        LocalDateTime start = yearMonth.atDay(1).atStartOfDay();
        LocalDateTime end = yearMonth.plusMonths(1).atDay(1).atStartOfDay();
        String period = yearMonth.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        String sql = "SELECT user_id, any(username) AS username, sum(bytes_in) AS byte_in, sum(bytes_out) AS byte_out " +
        "FROM default.access_log WHERE ts >= ? AND ts < ? GROUP BY user_id ORDER BY (byte_in + byte_out) DESC";
        return mapRows(client.query(sql, toTs(start), toTs(end)), period);
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

    private Timestamp toTs(LocalDateTime t) {
        return Timestamp.from(t.atZone(ZoneId.systemDefault()).toInstant());
    }

    private Long toLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private String toStr(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}