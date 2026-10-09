package org.congcong.controlmanager.telemetry;

import org.congcong.controlmanager.clickhouse.ClickHouseJdbcClient;
import org.springframework.stereotype.Service;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;

/** TopN over all unique ended sessions, with full-range denominators and UInt128 sums. */
@Service
public class DistributionService {
    private final ClickHouseJdbcClient client;
    private final TelemetryStore store;
    private record Dimension(String keys, String missing) { }
    private static final String USER = "coalesce(toString(user_id),'')", NAME = "coalesce(username,'')";
    private static final String TARGET = "coalesce(nullIf(logical_target_host,''),'')";
    private static final String ROUTE = "coalesce(toString(route_id),'')", ROUTE_NAME = "coalesce(route_name,'')";
    private static final Map<String, Dimension> DIMENSIONS = Map.of(
            "users", new Dimension("[" + USER + "," + NAME + "]", USER + " = '' AND " + NAME + " = ''"),
            "targets", new Dimension("[" + TARGET + "]", TARGET + " = ''"),
            "user_targets", new Dimension("[" + USER + "," + NAME + "," + TARGET + "]",
                    "(" + USER + " = '' AND " + NAME + " = '') OR " + TARGET + " = ''"),
            "routes", new Dimension("[" + ROUTE + "," + ROUTE_NAME + "]", ROUTE + " = '' AND " + ROUTE_NAME + " = ''"),
            "client_ips", new Dimension("[coalesce(client_ip,'')]", "coalesce(client_ip,'') = ''"),
            "categories", new Dimension("[coalesce(traffic_category,'UNKNOWN')]", "coalesce(traffic_category,'UNKNOWN') = 'UNKNOWN'"));
    private static final Map<String, String> METRICS = Map.of(
            "connections", "group_connections", "bytes", "group_upload + group_download", "upload", "group_upload", "download", "group_download");

    public DistributionService(ClickHouseJdbcClient client, TelemetryStore store) {
        this.client = client;
        this.store = store;
    }

    public record Counters(String connections, String upload, String download, String bytes) { }
    public record Item(List<String> keys, Counters counters, String value) { }
    public record Distribution(String from, String to, String dimension, String metric,
                               List<Item> items, Counters totals, String totalValue, String otherValue,
                               String groups, String missingConnections, String firstSessionAt,
                               String historyBasis, String timeBoundary) { }

    public Distribution aggregate(DistributionQuery request) {
        validate(request);
        store.requireQueryAvailable();
        Dimension dimension = DIMENSIONS.get(request.getDimension());
        String metric = METRICS.get(request.getMetric());
        StringBuilder where = new StringBuilder("worker_id = ? AND occurred_at >= ? AND occurred_at < ?");
        var params = new ArrayList<Object>(List.of(request.getWorkerId(), Timestamp.from(request.getFrom()), Timestamp.from(request.getTo())));
        filter(where, params, "coalesce(traffic_category,'UNKNOWN')", request.getTrafficCategory(), true);
        filter(where, params, "connect_outcome", request.getConnectOutcome(), true);
        filter(where, params, "username", request.getUsername(), false);
        filter(where, params, "client_ip", request.getClientIp(), false);
        params.add(request.getLimit());
        // Window aggregates run before LIMIT: the denominator includes every group, including unknowns.
        String sql = "WITH grouped AS (SELECT " + dimension.keys + " AS dimension_keys, count() AS group_connections, "
                + "sum(toUInt128(coalesce(upload_bytes,0))) AS group_upload, "
                + "sum(toUInt128(coalesce(download_bytes,0))) AS group_download, countIf(" + dimension.missing + ") AS missing "
                + "FROM " + TelemetrySchema.SESSIONS.table + " FINAL WHERE " + where + " GROUP BY dimension_keys) "
                + "SELECT toJSONString(dimension_keys) AS keys_json, toString(group_connections) AS connections, "
                + "toString(group_upload) AS upload, toString(group_download) AS download, toString(" + metric + ") AS value, "
                + "toString(sum(group_connections) OVER ()) AS total_connections, toString(sum(group_upload) OVER ()) AS total_upload, "
                + "toString(sum(group_download) OVER ()) AS total_download, toString(sum(" + metric + ") OVER ()) AS total_value, "
                + "toString(count() OVER ()) AS groups, toString(sum(missing) OVER ()) AS missing_connections "
                + "FROM grouped ORDER BY " + metric + " DESC, dimension_keys ASC LIMIT ?";
        var rows = client.query(sql, params.toArray());
        var items = new ArrayList<Item>();
        BigInteger top = BigInteger.ZERO;
        for (var row : rows) {
            List<String> keys;
            try {
                keys = new com.fasterxml.jackson.databind.ObjectMapper().readValue(row.get("keys_json").toString(),
                        new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
            } catch (Exception e) { throw new IllegalStateException("Invalid distribution keys", e); }
            String value = number(row, "value");
            items.add(new Item(keys, counters(row, ""), value));
            top = top.add(new BigInteger(value));
        }
        Map<String, Object> total = rows.isEmpty() ? Map.of() : rows.get(0);
        String totalValue = number(total, "total_value");
        var first = client.query("SELECT toString(minOrNull(occurred_at)) AS first_at FROM "
                + TelemetrySchema.SESSIONS.table + " FINAL WHERE worker_id = ?", request.getWorkerId());
        String firstAt = first.isEmpty() || first.get(0).get("first_at") == null ? null : first.get(0).get("first_at").toString();
        return new Distribution(request.getFrom().toString(), request.getTo().toString(), request.getDimension(), request.getMetric(),
                items, counters(total, "total_"), totalValue, new BigInteger(totalValue).subtract(top).toString(),
                number(total, "groups"), number(total, "missing_connections"), firstAt, "ENDED_SESSIONS_ONLY", "[from,to)");
    }

    private static void filter(StringBuilder where, List<Object> params, String column, String value, boolean all) {
        if (value != null && !value.isBlank() && !(all && value.equals("ALL"))) {
            where.append(" AND ").append(column).append(" = ?");
            params.add(value);
        }
    }

    private static Counters counters(Map<String, Object> row, String prefix) {
        String up = number(row, prefix + "upload"), down = number(row, prefix + "download");
        return new Counters(number(row, prefix + "connections"), up, down, new BigInteger(up).add(new BigInteger(down)).toString());
    }

    private static String number(Map<String, Object> row, String key) {
        return row.get(key) == null ? "0" : row.get(key).toString();
    }

    public static void validate(DistributionQuery request) {
        if (request.getWorkerId() == null || request.getWorkerId().isBlank() || request.getWorkerId().length() > 128
                || request.getFrom() == null || request.getTo() == null || !request.getFrom().isBefore(request.getTo())
                || Duration.between(request.getFrom(), request.getTo()).compareTo(Duration.ofDays(93)) > 0) {
            throw new IllegalArgumentException("请选择节点和不超过 93 天的时间范围");
        }
        if (!DIMENSIONS.containsKey(request.getDimension()) || !METRICS.containsKey(request.getMetric())
                || request.getLimit() < 1 || request.getLimit() > 100
                || !List.of("ALL", "EXTERNAL", "INTERNAL_REWRITE", "OTHER_INTERNAL", "UNKNOWN").contains(request.getTrafficCategory())
                || !List.of("ALL", "CONNECTED", "FAILED", "BLOCKED", "CANCELLED", "NOT_ATTEMPTED").contains(request.getConnectOutcome())) {
            throw new IllegalArgumentException("无效的维度、指标、分类、结果或 Top 数量");
        }
        for (String text : Arrays.asList(request.getUsername(), request.getClientIp())) {
            if (text != null && text.length() > 256) throw new IllegalArgumentException("筛选值过长");
        }
    }
}
