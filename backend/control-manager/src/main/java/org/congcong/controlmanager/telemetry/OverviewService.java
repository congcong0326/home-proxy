package org.congcong.controlmanager.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.WorkerMetricsDTO;
import org.congcong.common.telemetry.TrafficCategory;
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
@Slf4j
public class OverviewService {
    private static final String SESSIONS = TelemetrySchema.SESSIONS.table;
    private static final String DNS = TelemetrySchema.DNS.table;
    private static final String AUTH = TelemetrySchema.AUTH.table;
    private static final String CONNECTION_STATUS = "CASE WHEN connect_outcome = 'CONNECTED' THEN 'SUCCESS' "
            + "WHEN connect_outcome = 'FAILED' THEN 'FAILURE' WHEN connect_outcome = 'BLOCKED' THEN 'BLOCKED' "
            + "WHEN connect_outcome = 'CANCELLED' THEN 'CANCELLED' ELSE 'UNKNOWN' END";
    private static final String DNS_STATUS = "CASE WHEN outcome = 'BLOCKED' THEN 'BLOCKED' WHEN outcome = 'CANCELLED' THEN 'CANCELLED' "
            + "WHEN outcome = 'ERROR' OR (query_origin = 'CLIENT' AND response_sent = false) THEN 'FAILURE' "
            + "WHEN outcome = 'ANSWERED' AND (query_origin = 'PROXY_RESOLVE' OR response_sent = true) THEN 'SUCCESS' ELSE 'UNKNOWN' END";
    /** Only failed attempts are reported; legacy SUCCESS/CHALLENGE/CANCELLED rows are excluded by the filter. */
    static final String AUTH_FAILURE_FILTER = "auth_outcome IN ('FAILURE','DENIED','MALFORMED','ERROR')";
    private static final String AUTH_STATUS = "'FAILURE'";
    private final TelemetryJdbcClient client;
    private final TelemetryStore store;
    private final WorkerStatusRepository workers;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public OverviewService(TelemetryJdbcClient client, TelemetryStore store, WorkerStatusRepository workers) {
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
        ConcurrencyTimeline.Result concurrency;
        try {
            concurrency = concurrency(request, bucket);
        } catch (RuntimeException e) {
            log.warn("Historical concurrency unavailable for worker {}: {}", request.getWorkerId(), e.toString());
            concurrency = ConcurrencyTimeline.Result.unavailable();
        }
        var dns = counts(request, bucket, DNS, DNS_STATUS, request.getDnsResult(), false);
        var auth = counts(request, bucket, AUTH, AUTH_STATUS, "ALL", false);
        var result = new LinkedHashMap<String, Object>();
        result.put("from", request.getFrom().toString());
        result.put("to", request.getTo().toString());
        result.put("timeZone", request.getTimeZone());
        result.put("bucketSeconds", bucket);
        result.put("traffic", traffic);
        result.put("connections", connections);
        result.put("concurrency", concurrency);
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
        var rows = client.query("SELECT (occurred_at - ?) / ? AS bucket_index, "
                + "CAST(SUM(upload_bytes) AS TEXT) AS upload, CAST(SUM(download_bytes) AS TEXT) AS download "
                + "FROM " + SESSIONS + " WHERE " + range.where
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
        var periods = client.query("SELECT CAST(coalesce(SUM(CASE WHEN occurred_at >= ? THEN upload_bytes + download_bytes ELSE 0 END), 0) AS TEXT) AS daily, "
                + "CAST(coalesce(SUM(upload_bytes + download_bytes), 0) AS TEXT) AS monthly FROM " + SESSIONS
                + " WHERE " + monthly.where, periodParams.toArray());
        Scope categories = scope(request, request.getFrom(), request.getTo(), false);
        var categoryRows = client.query("SELECT coalesce(traffic_category, 'UNKNOWN') AS category, "
                + "CAST(SUM(upload_bytes + download_bytes) AS TEXT) AS bytes FROM " + SESSIONS
                + " WHERE " + categories.where + trafficResult(request)
                + " GROUP BY category", categories.params.toArray());
        var sourceRows = client.query("SELECT user_id, username, client_ip, CAST(SUM(upload_bytes) AS TEXT) AS upload, "
                + "CAST(SUM(download_bytes) AS TEXT) AS download, CAST(COUNT(*) AS TEXT) AS connections "
                + "FROM " + SESSIONS + " WHERE " + range.where
                + " GROUP BY user_id, username, client_ip ORDER BY SUM(upload_bytes + download_bytes) DESC, "
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
        if (table.equals(AUTH)) range.where += " AND " + AUTH_FAILURE_FILTER;
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
        var rows = client.query("SELECT (occurred_at - ?) / ? AS bucket_index, "
                + status + " AS result, CAST(COUNT(*) AS TEXT) AS count FROM " + table
                + " WHERE " + range.where + " GROUP BY bucket_index, result ORDER BY bucket_index, result", params.toArray());
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
        range.where += " AND connect_outcome = 'CONNECTED' AND connected_at < ?"
                + " AND ended_at > ? AND ended_at > connected_at";
        range.params.add(micros(request.getTo()));
        range.params.add(micros(request.getFrom()));
        range.where += categoryFilter(request) + trafficResult(request);
        if (!request.getTrafficCategory().equals("ALL")) range.params.add(request.getTrafficCategory());
        var params = new ArrayList<Object>(List.of(micros(request.getFrom()), micros(request.getTo()), bucket * 1_000_000L));
        params.addAll(range.params);
        var rows = client.query(ConcurrencyTimeline.query(range.where), params.toArray());
        return ConcurrencyTimeline.fromBuckets(request.getFrom(), request.getTo(), bucket, rows);
    }

    /** Reads the worker heartbeat (same source as the gateway monitor), never the telemetry store. */
    public Map<String, Object> live(OverviewQuery request) {
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
        } catch (Exception e) {
            return Map.of("available", false, "reason", "UNSUPPORTED");
        }
    }

    private String firstSession(OverviewQuery request) {
        var rows = client.query("SELECT MIN(occurred_at) AS first_at FROM " + SESSIONS
                + " WHERE worker_id = ?", request.getWorkerId());
        if (rows.isEmpty() || rows.get(0).get("first_at") == null) return "";
        return TelemetryJdbcClient.utcText(rows.get(0).get("first_at"));
    }
    private Scope scope(OverviewQuery request, Instant from, Instant to, boolean category) {
        Scope scope = sourceScope(request);
        scope.where += " AND occurred_at >= ? AND occurred_at < ?";
        scope.params.add(Timestamp.from(from));
        scope.params.add(Timestamp.from(to));
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
        if (hasText(request.getClientIp())) {
            scope.where += " AND client_ip = ?";
            scope.params.add(request.getClientIp());
        }
        if (hasText(request.getUsername())) {
            // Failed authentication has no verified user: include its submitted account only in auth facts.
            scope.where += authentication ? " AND (username = ? OR attempted_username = ?)" : " AND username = ?";
            scope.params.add(request.getUsername());
            if (authentication) scope.params.add(request.getUsername());
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
            var point = new LinkedHashMap<String, Object>();
            point.put("ts", time.toString());
            points.add(point);
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
        for (String result : List.of(request.getTrafficResult(), request.getConnectionResult(), request.getDnsResult())) {
            if (!List.of("ALL", "SUCCESS", "FAILURE", "BLOCKED", "CANCELLED", "UNKNOWN").contains(result)) {
                throw new IllegalArgumentException("INVALID_RESULT_FILTER");
            }
        }
        if (!List.of("ALL", "CLIENT", "PROXY_RESOLVE").contains(request.getDnsOrigin())) throw new IllegalArgumentException("INVALID_DNS_ORIGIN");
        if (hasText(request.getClientIp()) && !com.google.common.net.InetAddresses.isInetAddress(request.getClientIp())) throw new IllegalArgumentException("来源 IP 格式不正确");
        if (hasText(request.getUsername()) && request.getUsername().length() > 128) throw new IllegalArgumentException("账号过长");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static long micros(Instant time) {
        return time.getEpochSecond() * 1_000_000 + time.getNano() / 1000;
    }

    private static String number(Object value) {
        return value == null ? "0" : value.toString();
    }

    private static final class Scope {
        private String where;
        private final List<Object> params;

        private Scope(String where, List<Object> params) {
            this.where = where;
            this.params = params;
        }
    }
}
