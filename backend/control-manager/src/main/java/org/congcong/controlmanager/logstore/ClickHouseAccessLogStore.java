package org.congcong.controlmanager.logstore;

import lombok.RequiredArgsConstructor;
import org.congcong.common.dto.AccessLog;
import org.congcong.controlmanager.clickhouse.ClickHouseAccessLogWriter;
import org.congcong.controlmanager.clickhouse.ClickHouseJdbcClient;
import org.congcong.controlmanager.dto.*;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.*;

@Component
@RequiredArgsConstructor
public class ClickHouseAccessLogStore implements AccessLogStore {
    private final ClickHouseAccessLogWriter writer;
    private final ClickHouseJdbcClient client;

    @Override
    public int ingest(List<AccessLog> logs) {
        return writer.ingest(logs);
    }

    @Override
    public List<TimeSeriesPoint> getGlobalTrafficTrend(LocalDateTime from, LocalDateTime to) {
        String sql = "SELECT toStartOfMinute(ts) AS minute_time, sum(bytes_in) AS byte_in, sum(bytes_out) AS byte_out " +
                "FROM default.access_log WHERE ts >= ? AND ts <= ? GROUP BY minute_time ORDER BY minute_time";
        List<Map<String,Object>> rows = client.query(sql, toTimestamp(from), toTimestamp(to));
        List<TimeSeriesPoint> list = new ArrayList<>();
        for (Map<String,Object> r : rows) {
            list.add(new TimeSeriesPoint(toLocalDateTime(r.get("minute_time")), toLong(r.get("byte_in")), toLong(r.get("byte_out"))));
        }
        return list;
    }

    @Override
    public List<TimeSeriesPoint> getUserTrafficTrend(Long userId, LocalDateTime from, LocalDateTime to) {
        String sql = "SELECT toStartOfMinute(ts) AS minute_time, sum(bytes_in) AS byte_in, sum(bytes_out) AS byte_out " +
                "FROM default.access_log WHERE user_id = ? AND ts >= ? AND ts <= ? GROUP BY minute_time ORDER BY minute_time";
        List<Map<String,Object>> rows = client.query(sql, userId, toTimestamp(from), toTimestamp(to));
        List<TimeSeriesPoint> list = new ArrayList<>();
        for (Map<String,Object> r : rows) {
            list.add(new TimeSeriesPoint(toLocalDateTime(r.get("minute_time")), toLong(r.get("byte_in")), toLong(r.get("byte_out"))));
        }
        return list;
    }

    @Override
    public int cleanupExpiredMinuteTrafficStats() {
        return 0;
    }

    @Override
    public long countExpiredMinuteTrafficStats() {
        return 0L;
    }

    @Override
    public PageResponse<AccessLogListItem> queryAccessLogs(AccessLogQueryRequest req) {
        StringBuilder sb = new StringBuilder();
        List<Object> params = new ArrayList<>();
        sb.append("SELECT ");
        sb.append("ts, request_id, user_id, username, proxy_name, inbound_id, client_ip, status, bytes_in, bytes_out, request_duration_ms, original_target_host, rewrite_target_host, src_geo_country, dst_geo_country ,route_policy_name,route_policy_id ");
        sb.append("FROM default.access_log WHERE 1=1 ");
        buildWhere(req, sb, params);
        String orderBy = parseOrder(req.getSort());
        sb.append(" ORDER BY ").append(orderBy).append(" ");
        int page = req.getPage() == null ? 0 : Math.max(req.getPage(), 0);
        int size = req.getSize() == null ? 20 : Math.min(Math.max(req.getSize(), 1), 100);
        sb.append(" LIMIT ? OFFSET ?");
        params.add(size);
        params.add(page * size);
        List<Map<String,Object>> rows = client.query(sb.toString(), params.toArray());
        List<AccessLogListItem> items = new ArrayList<>(rows.size());
        for (Map<String,Object> r : rows) {
            items.add(new AccessLogListItem(
                    toLocalDateTime(r.get("ts")),
                    toStr(r.get("request_id")),
                    toLong(r.get("user_id")),
                    toStr(r.get("username")),
                    toStr(r.get("proxy_name")),
                    toLong(r.get("inbound_id")),
                    toStr(r.get("client_ip")),
                    toInt(r.get("status")),
                    toLong(r.get("bytes_in")),
                    toLong(r.get("bytes_out")),
                    toLong(r.get("request_duration_ms")),
                    toStr(r.get("original_target_host")),
                    toStr(r.get("rewrite_target_host")),
                    toStr(r.get("src_geo_country")),
                    toStr(r.get("dst_geo_country")),
                    toStr(r.get("route_policy_name")),
                    toLong(r.get("route_policy_id"))
            ));
        }
        StringBuilder countSql = new StringBuilder("SELECT count(*) AS cnt FROM default.access_log WHERE 1=1 ");
        List<Object> countParams = new ArrayList<>();
        buildWhere(req, countSql, countParams);
        List<Map<String,Object>> cntRows = client.query(countSql.toString(), countParams.toArray());
        long total = cntRows.isEmpty() ? 0L : toLong(cntRows.get(0).get("cnt"));
        return new PageResponse<>(items, page + 1, size, total);
    }

    @Override
    public Optional<AccessLogDetail> getAccessLogDetail(String id) {
        String sql = "SELECT ts, request_id, user_id, username, proxy_name, inbound_id, client_ip, client_port, src_geo_country, src_geo_city, original_target_host, original_target_ip, original_target_port, rewrite_target_host, rewrite_target_port, dst_geo_country, dst_geo_city, inbound_protocol_type, outbound_protocol_type, route_policy_name, route_policy_id, bytes_in, bytes_out, status, error_code, error_msg, request_duration_ms, dns_duration_ms, connect_duration_ms, connect_target_duration_ms FROM default.access_log WHERE  request_id = ? LIMIT 1";
        List<Map<String,Object>> rows = client.query(sql, id);
        if (rows.isEmpty()) return Optional.empty();
        Map<String,Object> r = rows.get(0);
        AccessLogDetail d = new AccessLogDetail(
                toLocalDateTime(r.get("ts")),
                toStr(r.get("request_id")),
                toLong(r.get("user_id")),
                toStr(r.get("username")),
                toStr(r.get("proxy_name")),
                toLong(r.get("inbound_id")),
                toStr(r.get("client_ip")),
                toInt(r.get("client_port")),
                toStr(r.get("src_geo_country")),
                toStr(r.get("src_geo_city")),
                toStr(r.get("original_target_host")),
                toStr(r.get("original_target_ip")),
                toInt(r.get("original_target_port")),
                toStr(r.get("rewrite_target_host")),
                toInt(r.get("rewrite_target_port")),
                toStr(r.get("dst_geo_country")),
                toStr(r.get("dst_geo_city")),
                toStr(r.get("inbound_protocol_type")),
                toStr(r.get("outbound_protocol_type")),
                toStr(r.get("route_policy_name")),
                toLong(r.get("route_policy_id")),
                toLong(r.get("bytes_in")),
                toLong(r.get("bytes_out")),
                toInt(r.get("status")),
                toStr(r.get("error_code")),
                toStr(r.get("error_msg")),
                toLong(r.get("request_duration_ms")),
                toLong(r.get("dns_duration_ms")),
                toLong(r.get("connect_duration_ms")),
                toLong(r.get("connect_target_duration_ms"))
        );
        return Optional.of(d);
    }


    @Override
    public List<TopItem> aggregateDailyTopRange(String from, String to, String dimension, String metric, int limit, Long userId) {
        LocalDateTime fromTs = parseDate(from);
        LocalDateTime toTs = parseDate(to);
        if (fromTs == null && toTs == null) {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime firstOfMonth = LocalDateTime.of(now.getYear(), now.getMonth(), 1, 0, 0, 0);
            LocalDateTime lastOfMonth = firstOfMonth.plusMonths(1).minusDays(1).withHour(23).withMinute(59).withSecond(59);
            fromTs = firstOfMonth;
            toTs = lastOfMonth;
        }
        String met = metric == null ? "requests" : metric;
        String dim = dimension == null ? "apps" : dimension;
        StringBuilder sb = new StringBuilder();
        List<Object> params = new ArrayList<>();
        sb.append("SELECT ");
        switch (dim) {
            case "users":
                sb.append("multiIf(length(username)>0, username, toString(user_id)) AS k, ");
                break;
            case "apps":
                sb.append("original_target_host AS k, ");
                break;
            case "user_apps":
                if (userId != null) {
                    sb.append("original_target_host AS k, ");
                } else {
                    sb.append("concat(multiIf(length(username)>0, username, toString(user_id)), '@', original_target_host) AS k, ");
                }
                break;
            case "src_geo":
                sb.append("concat(coalesce(src_geo_country,''), '/', coalesce(src_geo_city,'')) AS k, ");
                break;
            case "dst_geo":
                sb.append("concat(coalesce(dst_geo_country,''), '/', coalesce(dst_geo_city,'')) AS k, ");
                break;
            default:
                sb.append("original_target_host AS k, ");
                break;
        }
        if ("bytes".equals(met)) {
            sb.append("sum(bytes_in + bytes_out) AS v ");
        } else {
            sb.append("count() AS v ");
        }
        sb.append("FROM default.access_log WHERE 1=1 ");
        if (fromTs != null) { sb.append(" AND ts >= ?"); params.add(toTimestamp(fromTs)); }
        if (toTs != null) { sb.append(" AND ts <= ?"); params.add(toTimestamp(toTs)); }
        if (userId != null) { sb.append(" AND user_id = ?"); params.add(userId); }
        sb.append(" GROUP BY k ORDER BY v DESC LIMIT ?");
        params.add(limit <= 0 ? 10 : Math.min(limit, 100));
        List<Map<String,Object>> rows = client.query(sb.toString(), params.toArray());
        List<TopItem> items = new ArrayList<>();
        for (Map<String,Object> r : rows) {
            items.add(new TopItem(toStr(r.get("k")), toLong(r.get("v"))));
        }
        return items;
    }

    private void buildWhere(AccessLogQueryRequest req, StringBuilder sb, List<Object> params) {
        if (req.getFrom() != null && !req.getFrom().isBlank()) { sb.append(" AND ts >= ?"); params.add(toTimestamp(parseDate(req.getFrom()))); }
        if (req.getTo() != null && !req.getTo().isBlank()) { sb.append(" AND ts <= ?"); params.add(toTimestamp(parseDate(req.getTo()))); }
        if (req.getUserId() != null) { sb.append(" AND user_id = ?"); params.add(req.getUserId()); }
        if (notBlank(req.getUsername())) { sb.append(" AND username = ?"); params.add(req.getUsername()); }
        if (notBlank(req.getProxyName())) { sb.append(" AND proxy_name = ?"); params.add(req.getProxyName()); }
        if (req.getInboundId() != null) { sb.append(" AND inbound_id = ?"); params.add(req.getInboundId()); }
        if (notBlank(req.getClientIp())) { sb.append(" AND client_ip = ?"); params.add(req.getClientIp()); }
        if (req.getStatus() != null) { sb.append(" AND status = ?"); params.add(req.getStatus()); }
        if (notBlank(req.getProtocol())) {
            if ("inbound".equalsIgnoreCase(req.getProtocol())) sb.append(" AND inbound_protocol_type != ''");
            else if ("outbound".equalsIgnoreCase(req.getProtocol())) sb.append(" AND outbound_protocol_type != ''");
        }
        if (req.getRoutePolicyId() != null) { sb.append(" AND route_policy_id = ?"); params.add(req.getRoutePolicyId()); }
        if (notBlank(req.getSrcGeoCountry())) { sb.append(" AND src_geo_country = ?"); params.add(req.getSrcGeoCountry()); }
        if (notBlank(req.getSrcGeoCity())) { sb.append(" AND src_geo_city = ?"); params.add(req.getSrcGeoCity()); }
        if (notBlank(req.getDstGeoCountry())) { sb.append(" AND dst_geo_country = ?"); params.add(req.getDstGeoCountry()); }
        if (notBlank(req.getDstGeoCity())) { sb.append(" AND dst_geo_city = ?"); params.add(req.getDstGeoCity()); }
        if (notBlank(req.getHost())) { sb.append(" AND (original_target_host = ? OR rewrite_target_host = ?)"); params.add(req.getHost()); params.add(req.getHost()); }
        if (notBlank(req.getOriginalTargetHost())) { sb.append(" AND original_target_host = ?"); params.add(req.getOriginalTargetHost()); }
        if (notBlank(req.getRewriteTargetHost())) { sb.append(" AND rewrite_target_host = ?"); params.add(req.getRewriteTargetHost()); }
        if (notBlank(req.getQ())) {
            sb.append(" AND (client_ip LIKE ? OR username LIKE ? OR proxy_name LIKE ? OR original_target_host LIKE ? OR rewrite_target_host LIKE ? OR error_msg LIKE ?)");
            String like = "%" + req.getQ().trim() + "%";
            params.add(like); params.add(like); params.add(like); params.add(like); params.add(like); params.add(like);
        }
    }

    private String parseOrder(String sort) {
        if (sort == null || sort.isBlank()) return "ts DESC";
        String[] parts = sort.split(",");
        if (parts.length == 2) {
            String dir = "asc".equalsIgnoreCase(parts[1]) ? "ASC" : "DESC";
            return parts[0] + " " + dir;
        }
        return sort + " DESC";
    }

    private boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private LocalDateTime parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            if (s.matches("^\\d{13}$")) {
                long ms = Long.parseLong(s);
                return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault());
            }
            if (s.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                int y = Integer.parseInt(s.substring(0, 4));
                int m = Integer.parseInt(s.substring(5, 7));
                int d = Integer.parseInt(s.substring(8, 10));
                return LocalDateTime.of(y, m, d, 0, 0, 0);
            }
            try {
                return LocalDateTime.parse(s, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException ignored) {}
            Instant instant = Instant.parse(s);
            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        } catch (DateTimeParseException | NumberFormatException ex) {
            return null;
        }
    }

    private Timestamp toTimestamp(LocalDateTime t) {
        if (t == null) return Timestamp.from(Instant.now());
        return Timestamp.valueOf(t);
    }

    private LocalDateTime toLocalDateTime(Object o) {
        if (o == null) return LocalDateTime.now();
        if (o instanceof Timestamp) return ((Timestamp)o).toLocalDateTime();
        if (o instanceof java.time.OffsetDateTime) return ((java.time.OffsetDateTime)o).toLocalDateTime();
        if (o instanceof LocalDateTime) return (LocalDateTime)o;
        return LocalDateTime.now();
    }

    private String toStr(Object o) { return o == null ? null : String.valueOf(o); }
    private Long toLong(Object o) { return o == null ? null : ((Number)o).longValue(); }
    private Integer toInt(Object o) { return o == null ? null : ((Number)o).intValue(); }
}