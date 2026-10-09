package org.congcong.controlmanager.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.congcong.common.dto.WorkerMetricsDTO;
import org.congcong.common.telemetry.TrafficCategory;
import org.congcong.controlmanager.clickhouse.ClickHouseJdbcClient;
import org.congcong.controlmanager.repository.WorkerStatusRepository;
import org.springframework.stereotype.Service;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/** Independent, deduplicated V1 aggregates. No legacy fallback or audit-page sampling. */
@Service
public class OverviewService {
    private static final String SESSIONS = "telemetry.proxy_session_log_v1";
    private static final String DNS = "telemetry.dns_query_log_v1";
    private static final String AUTH = "telemetry.credential_auth_log_v1";
    private static final String CONNECTION_STATUS = "multiIf(connect_outcome = 'CONNECTED', 'SUCCESS', "
            + "connect_outcome = 'FAILED', 'FAILURE', connect_outcome = 'BLOCKED', 'BLOCKED', "
            + "connect_outcome = 'CANCELLED', 'CANCELLED', 'UNKNOWN')";
    private static final String DNS_STATUS = "multiIf(outcome = 'BLOCKED', 'BLOCKED', outcome = 'CANCELLED', 'CANCELLED', "
            + "outcome = 'ERROR' OR (query_origin = 'CLIENT' AND response_sent = false), 'FAILURE', "
            + "outcome = 'ANSWERED' AND (query_origin = 'PROXY_RESOLVE' OR response_sent = true), 'SUCCESS', 'UNKNOWN')";
    private static final String AUTH_STATUS = "multiIf(auth_outcome = 'SUCCESS', 'SUCCESS', "
            + "auth_outcome IN ('FAILURE','DENIED','MALFORMED','ERROR'), 'FAILURE', "
            + "auth_outcome = 'CHALLENGE', 'CHALLENGE', auth_outcome = 'CANCELLED', 'CANCELLED', 'UNKNOWN')";
    private final ClickHouseJdbcClient client;
    private final TelemetryStore store;
    private final WorkerStatusRepository workers;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public OverviewService(ClickHouseJdbcClient client, TelemetryStore store, WorkerStatusRepository workers) {
        this.client = client;
        this.store = store;
        this.workers = workers;
    }

    public Map<String, Object> overview(OverviewQuery request) {
        validate(request);
        store.requireQueryAvailable();
        long seconds = Duration.between(request.getFrom(), request.getTo()).getSeconds();
        int bucket = seconds <= 3600 ? 60 : seconds <= 10800 ? 180 : seconds <= 86400 ? 900 : seconds <= 604800 ? 7200 : 21600;
        var traffic = traffic(request, bucket);
        var connections = counts(request, bucket, SESSIONS, CONNECTION_STATUS, request.getConnectionResult(), true);
        // Connection-result filters select attempts, never pretend failed attempts are concurrent tunnels.
        var concurrency = concurrency(request, bucket);
        var dns = counts(request, bucket, DNS, DNS_STATUS, request.getDnsResult(), false);
        var auth = counts(request, bucket, AUTH, AUTH_STATUS, request.getAuthResult(), false);
        var result = new LinkedHashMap<String, Object>();
        result.put("from", request.getFrom().toString());
        result.put("to", request.getTo().toString());
        result.put("timeZone", request.getTimeZone());
        result.put("bucketSeconds", bucket);
        result.put("traffic", traffic);
        result.put("connections", connections);
        result.put("concurrency", concurrency);
        result.put("live", live(request));
        result.put("dns", dns);
        result.put("auth", auth);
        result.put("historyBasis", "ENDED_SESSIONS_ONLY");
        result.put("firstSessionAt", firstSession(request));
        return result;
    }

    private Map<String, Object> traffic(OverviewQuery request, int bucket) {
        Scope range = scope(request, request.getFrom(), request.getTo(), true);
        var params = new ArrayList<Object>(List.of(micros(request.getFrom()), bucket * 1_000_000L));
        params.addAll(range.params);
        var rows = client.query("SELECT intDiv(toUnixTimestamp64Micro(occurred_at) - ?, ?) AS bucket_index, "
                + "toString(sum(upload_bytes)) AS upload, toString(sum(download_bytes)) AS download "
                + "FROM " + SESSIONS + " FINAL WHERE " + range.where
                + " GROUP BY bucket_index ORDER BY bucket_index", params.toArray());
        var series = fill(request, bucket);
        BigInteger upload = BigInteger.ZERO, download = BigInteger.ZERO;
        for (var row : rows) {
            int index = ((Number) row.get("bucket_index")).intValue();
            if (index >= 0 && index < series.size()) {
                series.get(index).put("upload", number(row.get("upload")));
                series.get(index).put("download", number(row.get("download")));
            }
            upload = upload.add(new BigInteger(number(row.get("upload"))));
            download = download.add(new BigInteger(number(row.get("download"))));
        }
        Instant now = Instant.now();
        var local = now.atZone(ZoneId.of(request.getTimeZone()));
        Instant today = local.toLocalDate().atStartOfDay(local.getZone()).toInstant();
        Instant month = local.toLocalDate().withDayOfMonth(1).atStartOfDay(local.getZone()).toInstant();
        Scope monthly = scope(request, month, now, true);
        var periodParams = new ArrayList<Object>(List.of(Timestamp.from(today)));
        periodParams.addAll(monthly.params);
        var periods = client.query("SELECT toString(sumIf(upload_bytes + download_bytes, occurred_at >= ?)) AS daily, "
                + "toString(sum(upload_bytes + download_bytes)) AS monthly FROM " + SESSIONS
                + " FINAL WHERE " + monthly.where, periodParams.toArray());
        Scope categories = scope(request, request.getFrom(), request.getTo(), false);
        var categoryRows = client.query("SELECT coalesce(traffic_category, 'UNKNOWN') AS category, "
                + "toString(sum(upload_bytes + download_bytes)) AS bytes FROM " + SESSIONS
                + " FINAL WHERE " + categories.where + trafficResult(request)
                + " GROUP BY category", categories.params.toArray());
        var sourceRows = client.query("SELECT user_id, username, client_ip, toString(sum(upload_bytes)) AS upload, "
                + "toString(sum(download_bytes)) AS download, toString(count()) AS connections "
                + "FROM " + SESSIONS + " FINAL WHERE " + range.where
                + " GROUP BY user_id, username, client_ip ORDER BY sum(upload_bytes + download_bytes) DESC, "
                + "coalesce(username,''), coalesce(client_ip,''), coalesce(user_id,0) LIMIT 20", range.params.toArray());
        var sources = new ArrayList<Map<String, Object>>();
        for (var row : sourceRows) {
            var source = new LinkedHashMap<String, Object>();
            source.put("userId", row.get("user_id") == null ? null : number(row.get("user_id")));
            source.put("username", row.get("username"));
            source.put("clientIp", row.get("client_ip"));
            for (String field : List.of("upload", "download", "connections")) source.put(field, number(row.get(field)));
            sources.add(source);
        }
        Map<String, Object> period = periods.isEmpty() ? Map.of() : periods.get(0);
        return Map.of("upload", upload.toString(), "download", download.toString(), "total", upload.add(download).toString(),
                "daily", number(period.get("daily")), "monthly", number(period.get("monthly")),
                "series", series, "sources", sources, "categories", categoryRows);
    }

    private Map<String, Object> counts(OverviewQuery request, int bucket, String table, String status,
                                      String selectedResult, boolean category) {
        Scope range = sourceScope(request, table.equals(AUTH));
        range.where += " AND occurred_at >= ? AND occurred_at < ?";
        range.params.add(Timestamp.from(request.getFrom()));
        range.params.add(Timestamp.from(request.getTo()));
        if (category) {
            range.where += categoryFilter(request);
            if (!request.getTrafficCategory().equals("ALL")) range.params.add(request.getTrafficCategory());
        }
        if (table.equals(DNS) && !request.getDnsOrigin().equals("ALL")) {
            range.where += " AND query_origin = ?";
            range.params.add(request.getDnsOrigin());
        }
        if (!selectedResult.equals("ALL")) {
            range.where += " AND (" + status + ") = ?";
            range.params.add(selectedResult);
        }
        var params = new ArrayList<Object>(List.of(micros(request.getFrom()), bucket * 1_000_000L));
        params.addAll(range.params);
        var rows = client.query("SELECT intDiv(toUnixTimestamp64Micro(occurred_at) - ?, ?) AS bucket_index, "
                + status + " AS result, toString(count()) AS count FROM " + table
                + " FINAL WHERE " + range.where + " GROUP BY bucket_index, result ORDER BY bucket_index, result", params.toArray());
        var series = fill(request, bucket);
        var totals = new LinkedHashMap<String, String>();
        for (var row : rows) {
            String key = row.get("result").toString();
            String count = number(row.get("count"));
            int index = ((Number) row.get("bucket_index")).intValue();
            if (index >= 0 && index < series.size()) series.get(index).put(key, count);
            totals.merge(key, count, (a, b) -> new BigInteger(a).add(new BigInteger(b)).toString());
        }
        String total = totals.values().stream().map(BigInteger::new).reduce(BigInteger.ZERO, BigInteger::add).toString();
        return Map.of("series", series, "totals", totals, "total", total);
    }

    private ConcurrencyTimeline.Result concurrency(OverviewQuery request, int bucket) {
        Scope range = sourceScope(request);
        range.where += " AND connect_outcome = 'CONNECTED' AND connected_at < ? AND ended_at > ? AND ended_at > connected_at";
        range.params.add(Timestamp.from(request.getTo()));
        range.params.add(Timestamp.from(request.getFrom()));
        range.where += categoryFilter(request) + trafficResult(request);
        if (!request.getTrafficCategory().equals("ALL")) range.params.add(request.getTrafficCategory());
        var params = new ArrayList<Object>(range.params);
        params.add(Timestamp.from(request.getFrom()));
        params.add(Timestamp.from(request.getTo()));
        var deltas = client.query("WITH intervals AS (SELECT connected_at, ended_at FROM " + SESSIONS
                + " FINAL WHERE " + range.where + ") SELECT event_us, sum(delta) AS delta FROM ("
                + "SELECT toUnixTimestamp64Micro(greatest(connected_at, toDateTime64(?,6,'UTC'))) AS event_us, toInt64(1) AS delta FROM intervals "
                + "UNION ALL SELECT toUnixTimestamp64Micro(least(ended_at, toDateTime64(?,6,'UTC'))) AS event_us, toInt64(-1) AS delta FROM intervals) "
                + "GROUP BY event_us ORDER BY event_us LIMIT 100001", params.toArray());
        if (deltas.size() > 100000) throw new IllegalStateException("CONCURRENCY_RANGE_TOO_DENSE");
        return ConcurrencyTimeline.calculate(request.getFrom(), request.getTo(), bucket, deltas);
    }

    public Map<String, Object> live(OverviewQuery request) {
        store.requireQueryAvailable();
        var status = workers.findById(request.getWorkerId()).orElse(null);
        if (status == null || status.getMetricsJson() == null) return Map.of("available", false, "reason", "UNSUPPORTED");
        try {
            var metrics = mapper.readValue(status.getMetricsJson(), WorkerMetricsDTO.class);
            var sample = metrics.getEstablishedConnectionsV1();
            if (sample == null) return Map.of("available", false, "reason", "UNSUPPORTED");
            Instant now = Instant.now();
            if (sample.sampledAt() == null || sample.sampledAt().isBefore(now.minusSeconds(15))
                    || sample.sampledAt().isAfter(now.plusSeconds(10))) return Map.of("available", false, "reason", "STALE");
            boolean filtered = hasText(request.getUsername()) || hasText(request.getClientIp());
            if (filtered && sample.groupsTruncated()) return Map.of("available", false, "reason", "GROUPS_TRUNCATED");
            long count = filtered ? sample.groups().stream()
                    .filter(g -> request.getTrafficCategory().equals("ALL") || g.trafficCategory().name().equals(request.getTrafficCategory()))
                    .filter(g -> !hasText(request.getUsername()) || request.getUsername().equals(g.username()))
                    .filter(g -> !hasText(request.getClientIp()) || request.getClientIp().equals(g.clientIp()))
                    .mapToLong(g -> g.count()).sum()
                    : sample.byCategory().entrySet().stream()
                    .filter(e -> request.getTrafficCategory().equals("ALL") || e.getKey().name().equals(request.getTrafficCategory()))
                    .mapToLong(Map.Entry::getValue).sum();
            return Map.of("available", true, "count", Long.toString(count), "sampledAt", sample.sampledAt().toString());
        } catch (Exception e) { return Map.of("available", false, "reason", "UNSUPPORTED"); }
    }

    private String firstSession(OverviewQuery request) {
        var rows = client.query("SELECT toString(minOrNull(occurred_at)) AS first_at FROM " + SESSIONS
                + " FINAL WHERE worker_id = ?", request.getWorkerId());
        if (rows.isEmpty() || rows.get(0).get("first_at") == null) return "";
        return rows.get(0).get("first_at").toString();
    }
    private Scope scope(OverviewQuery request, Instant from, Instant to, boolean category) {
        Scope scope = sourceScope(request);
        scope.where += " AND occurred_at >= ? AND occurred_at < ?";
        scope.params.add(Timestamp.from(from)); scope.params.add(Timestamp.from(to));
        if (category) {
            scope.where += categoryFilter(request) + trafficResult(request);
            if (!request.getTrafficCategory().equals("ALL")) scope.params.add(request.getTrafficCategory());
        }
        return scope;
    }
    private Scope sourceScope(OverviewQuery request) {
        return sourceScope(request, false);
    }
    private Scope sourceScope(OverviewQuery request, boolean authentication) {
        Scope scope = new Scope("worker_id = ?", new ArrayList<>(List.of(request.getWorkerId())));
        if (hasText(request.getClientIp())) { scope.where += " AND client_ip = ?"; scope.params.add(request.getClientIp()); }
        if (hasText(request.getUsername())) {
            // Failed authentication has no verified user: include its submitted account only in auth facts.
            scope.where += authentication ? " AND (username = ? OR attempted_username = ? OR verified_username = ?)" : " AND username = ?";
            scope.params.add(request.getUsername());
            if (authentication) {
                scope.params.add(request.getUsername()); scope.params.add(request.getUsername());
            }
        }
        return scope;
    }
    private static String categoryFilter(OverviewQuery request) {
        return request.getTrafficCategory().equals("ALL") ? "" : " AND coalesce(traffic_category,'UNKNOWN') = ?";
    }
    private static String trafficResult(OverviewQuery request) {
        return switch (request.getTrafficResult()) {
            case "SUCCESS" -> " AND session_outcome = 'NORMAL_END'";
            case "FAILURE" -> " AND session_outcome = 'ERROR_END'";
            default -> "";
        };
    }
    private static List<Map<String, Object>> fill(OverviewQuery request, int bucket) {
        var points = new ArrayList<Map<String, Object>>();
        for (Instant time = request.getFrom(); time.isBefore(request.getTo()); time = time.plusSeconds(bucket)) {
            var point = new LinkedHashMap<String, Object>(); point.put("ts", time.toString()); points.add(point);
        }
        return points;
    }
    public static void validate(OverviewQuery request) {
        if (!hasText(request.getWorkerId()) || request.getWorkerId().length() > 128 || request.getFrom() == null || request.getTo() == null
                || Duration.between(request.getFrom(), request.getTo()).compareTo(Duration.ofSeconds(60)) < 0
                || Duration.between(request.getFrom(), request.getTo()).compareTo(Duration.ofDays(31)) > 0) {
            throw new IllegalArgumentException("选择节点及 1 分钟至 31 天的时间范围");
        }
        ZoneId.of(request.getTimeZone());
        if (!request.getTrafficCategory().equals("ALL")) TrafficCategory.valueOf(request.getTrafficCategory());
        if (!List.of("ALL", "SUCCESS", "FAILURE").contains(request.getTrafficResult())) throw new IllegalArgumentException("INVALID_TRAFFIC_RESULT");
        for (String result : List.of(request.getTrafficResult(), request.getConnectionResult(), request.getDnsResult(), request.getAuthResult())) {
            if (!List.of("ALL", "SUCCESS", "FAILURE", "BLOCKED", "CANCELLED", "CHALLENGE", "UNKNOWN").contains(result)) {
                throw new IllegalArgumentException("INVALID_RESULT_FILTER");
            }
        }
        if (!List.of("ALL", "CLIENT", "PROXY_RESOLVE").contains(request.getDnsOrigin())) throw new IllegalArgumentException("INVALID_DNS_ORIGIN");
        if (hasText(request.getClientIp()) && !com.google.common.net.InetAddresses.isInetAddress(request.getClientIp())) throw new IllegalArgumentException("来源 IP 格式不正确");
        if (hasText(request.getUsername()) && request.getUsername().length() > 128) throw new IllegalArgumentException("账号过长");
    }
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private static long micros(Instant time) { return time.getEpochSecond() * 1_000_000 + time.getNano() / 1000; }
    private static String number(Object value) { return value == null ? "0" : value.toString(); }
    private static final class Scope {
        private String where;
        private final List<Object> params;
        private Scope(String where, List<Object> params) { this.where = where; this.params = params; }
    }
}
