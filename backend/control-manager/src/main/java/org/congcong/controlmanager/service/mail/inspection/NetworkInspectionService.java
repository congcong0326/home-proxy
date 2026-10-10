package org.congcong.controlmanager.service.mail.inspection;

import lombok.RequiredArgsConstructor;
import org.congcong.controlmanager.clickhouse.ClickHouseJdbcClient;
import org.congcong.controlmanager.telemetry.TelemetryStore;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

import static org.congcong.controlmanager.service.mail.inspection.InspectionFormats.*;

@Service
@RequiredArgsConstructor
public class NetworkInspectionService {
    private static final String SESSIONS = "telemetry.proxy_session_log_v1";
    private static final String DNS = "telemetry.dns_query_log_v1";
    private static final String DNS_FAILURE = "(outcome = 'ERROR' OR response_sent = false) "
            + "AND ifNull(outcome, '') NOT IN ('BLOCKED', 'CANCELLED')";
    private final ClickHouseJdbcClient client;
    private final TelemetryStore store;
    private final InspectionProperties properties;

    public InspectionSection connections(List<String> workers, Instant from, Instant to, ZoneId zone) {
        String title = "代理连接质量";
        if (workers.isEmpty()) return empty(title);
        store.requireQueryAvailable();
        Scope scope = scope(workers, from, to);
        var rows = client.query("""
                SELECT worker_id, toString(count()) AS records,
                  toString(countIf(connect_outcome = 'CONNECTED')) AS connected,
                  toString(countIf(connect_outcome = 'FAILED')) AS failed,
                  toString(countIf(connect_outcome = 'BLOCKED')) AS blocked,
                  toString(countIf(connect_outcome = 'CANCELLED')) AS cancelled,
                  toString(countIf(connect_outcome = 'CONNECTED' AND session_outcome = 'ERROR_END')) AS transfer_errors,
                  toString(countIf(connect_outcome = 'CONNECTED' AND channel_setup_duration_us IS NOT NULL)) AS timing_samples,
                  quantileExactIf(0.95)(ifNull(channel_setup_duration_us, 0),
                    connect_outcome = 'CONNECTED' AND channel_setup_duration_us IS NOT NULL) AS setup_p95
                FROM telemetry.proxy_session_log_v1 FINAL WHERE
                """ + scope.where + " GROUP BY worker_id ORDER BY worker_id", scope.params.toArray());
        Map<String, Map<String, Object>> byWorker = index(rows);
        var starts = historyStarts(SESSIONS, workers, "");
        var items = new ArrayList<InspectionSection.Item>();
        for (String worker : workers) {
            var row = byWorker.getOrDefault(worker, Map.of());
            BigInteger connected = integer(row.get("connected")), failed = integer(row.get("failed"));
            items.add(InspectionSection.item("网关 " + worker,
                    "结束连接记录", integer(row.get("records")).toString(),
                    "建连成功 / 失败", connected + " / " + failed,
                    "建连失败率", rate(failed, connected.add(failed)),
                    "策略阻断 / 客户端取消", integer(row.get("blocked")) + " / " + integer(row.get("cancelled")),
                    "建连后传输错误", integer(row.get("transfer_errors")).toString(),
                    "成功建连耗时 P95", milliseconds(row.get("setup_p95"), row.get("timing_samples")),
                    "历史覆盖", coverage(starts.get(worker), from, zone)));
        }
        var failures = client.query("""
                SELECT worker_id, coalesce(failure_stage, 'UNKNOWN') AS stage,
                  coalesce(error_code, 'UNKNOWN') AS error, coalesce(route_name, '') AS route,
                  coalesce(outbound_node_id, '') AS outbound,
                  coalesce(logical_target_host, effective_target_host, '') AS target,
                  toString(count()) AS records, min(connection_id) AS example_connection
                FROM telemetry.proxy_session_log_v1 FINAL WHERE
                """ + scope.where + " AND (connect_outcome = 'FAILED' OR session_outcome = 'ERROR_END')"
                + " GROUP BY worker_id, stage, error, route, outbound, target ORDER BY count() DESC, worker_id, stage, error LIMIT 10",
                scope.params.toArray());
        for (var row : failures) {
            items.add(InspectionSection.item("主要错误：" + text(row.get("worker_id")) + " / " + text(row.get("stage")),
                    "错误码 / 次数", text(row.get("error")) + " / " + text(row.get("records")),
                    "路由 / 上游", text(row.get("route")) + " / " + text(row.get("outbound")),
                    "目标", text(row.get("target")), "样本连接 ID", text(row.get("example_connection"))));
        }
        return new InspectionSection(title, items, List.of(
                "连接数来自唯一结束汇总；失败率 = FAILED / (CONNECTED + FAILED)，阻断、取消不混入分母。",
                "通道建立成功不代表网站业务成功；未结束的长连接、丢失或尚未落库的汇总不在本次统计中。",
                "主要错误最多展示 10 组；上面的数量和比例使用全量记录，不从错误排行求和。"));
    }

    public InspectionSection traffic(List<String> workers, Instant from, Instant to, ZoneId zone) {
        String title = "外网上下行流量";
        if (workers.isEmpty()) return empty(title);
        store.requireQueryAvailable();
        Scope scope = scope(workers, from, to);
        var rows = client.query("""
                SELECT worker_id,
                  toString(sumIf(toUInt128(ifNull(upload_bytes, 0)), traffic_category = 'EXTERNAL')) AS upload,
                  toString(sumIf(toUInt128(ifNull(download_bytes, 0)), traffic_category = 'EXTERNAL')) AS download,
                  toString(countIf(traffic_category = 'EXTERNAL')) AS external_records,
                  toString(sumIf(toUInt128(ifNull(upload_bytes, 0)) + toUInt128(ifNull(download_bytes, 0)),
                    ifNull(traffic_category, 'UNKNOWN') = 'UNKNOWN')) AS unknown_bytes
                FROM telemetry.proxy_session_log_v1 FINAL WHERE
                """ + scope.where + " GROUP BY worker_id ORDER BY worker_id", scope.params.toArray());
        var byWorker = index(rows);
        var starts = historyStarts(SESSIONS, workers, "");
        var items = new ArrayList<InspectionSection.Item>();
        var ordered = new ArrayList<>(workers);
        ordered.sort(Comparator.comparing((String id) -> !id.equals(properties.getPrimaryWorkerId())).thenComparing(id -> id));
        for (String worker : ordered) {
            var row = byWorker.getOrDefault(worker, Map.of());
            BigInteger upload = integer(row.get("upload")), download = integer(row.get("download"));
            items.add(InspectionSection.item((worker.equals(properties.getPrimaryWorkerId()) ? "主网关总览 " : "其他节点 ") + worker,
                    "外网上行", bytes(upload), "外网下行", bytes(download), "外网合计", bytes(upload.add(download)),
                    "外网结束连接数", integer(row.get("external_records")).toString(),
                    "未分类流量（不计外网）", bytes(integer(row.get("unknown_bytes"))),
                    "历史覆盖", coverage(starts.get(worker), from, zone)));
        }
        var notes = new ArrayList<String>(List.of(
                "仅统计 EXTERNAL，排除重写至内网等其他分类；上行是客户端到目标，下行是目标到客户端。",
                "按连接结束时间入账，统计成功转发的载荷；不是网卡吞吐或宽带账单流量。",
                "以主网关为总览，其他节点分列。多级代理可能重复观测同一流量，不跨节点相加。"));
        if (!workers.contains(properties.getPrimaryWorkerId())) {
            notes.add("主网关 " + properties.getPrimaryWorkerId() + " 未配置或未启用，主网关总览不可用。");
        }
        return new InspectionSection(title, items, notes);
    }

    public InspectionSection dns(List<String> workers, Instant from, Instant to, ZoneId zone) {
        String title = "客户端 DNS 处理质量";
        if (workers.isEmpty()) return empty(title);
        store.requireQueryAvailable();
        Scope scope = scope(workers, from, to);
        var rows = client.query("SELECT worker_id, coalesce(answer_mode, 'UNKNOWN') AS mode, toString(count()) AS records, "
                + "toString(countIf(outcome = 'ANSWERED' AND response_sent = true)) AS answered, "
                + "toString(countIf(" + DNS_FAILURE + ")) AS errors, "
                + "toString(countIf(duration_us IS NOT NULL)) AS timing_samples, "
                + "quantileExactIf(0.95)(ifNull(duration_us, 0), duration_us IS NOT NULL) AS p95 "
                + "FROM " + DNS + " FINAL WHERE " + scope.where + " AND query_origin = 'CLIENT'"
                + " GROUP BY worker_id, mode ORDER BY worker_id, mode", scope.params.toArray());
        var starts = historyStarts(DNS, workers, " AND query_origin = 'CLIENT'");
        var items = new ArrayList<InspectionSection.Item>();
        for (var row : rows) {
            BigInteger errors = integer(row.get("errors")), answered = integer(row.get("answered"));
            items.add(InspectionSection.item("网关 " + text(row.get("worker_id")) + " / 应答模式 " + text(row.get("mode")),
                    "客户端查询量", text(row.get("records")), "已应答 / 服务错误", answered + " / " + errors,
                    "服务错误率", rate(errors, answered.add(errors)),
                    "处理耗时 P95", milliseconds(row.get("p95"), row.get("timing_samples")),
                    "历史覆盖", coverage(starts.get(row.get("worker_id")), from, zone)));
        }
        for (String worker : workers) {
            if (rows.stream().noneMatch(row -> worker.equals(row.get("worker_id")))) {
                items.add(InspectionSection.item("网关 " + worker, "客户端查询量", "0（窗口内无记录）",
                        "历史覆盖", coverage(starts.get(worker), from, zone)));
            }
        }
        return new InspectionSection(title, items, List.of(
                "REAL、FAKE_IP 等应答模式分别统计；耗时是客户端 DNS 处理耗时，不等于上游解析耗时。",
                "正常 NXDOMAIN、预期 AAAA 空回答不计服务错误；阻断与取消不混入错误率分母。",
                "现有采集不提供准确的每日超时数或异常上游解析端点，本报告不推测这些数值。"));
    }

    private Map<String, Instant> historyStarts(String table, List<String> workers, String extra) {
        var rows = client.query("SELECT worker_id, min(occurred_at) AS first_at FROM " + table
                + " FINAL WHERE " + workerScope(workers) + extra + " GROUP BY worker_id", workers.toArray());
        Map<String, Instant> starts = new HashMap<>();
        for (var row : rows) starts.put(row.get("worker_id").toString(), instant(row.get("first_at")));
        return starts;
    }

    private static String coverage(Instant start, Instant from, ZoneId zone) {
        if (start == null) return "尚无历史记录，不能据此判断网络正常";
        return "最早记录 " + time(start, zone) + (start.isAfter(from) ? "；本窗口历史不足" : "；不保证此后没有采集缺口");
    }

    private static Map<String, Map<String, Object>> index(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (var row : rows) result.put(row.get("worker_id").toString(), row);
        return result;
    }

    private static InspectionSection empty(String title) {
        return new InspectionSection(title, List.of(), List.of("没有启用的网关，无法进行本项巡检。"));
    }

    private record Scope(String where, List<Object> params) { }

    private static Scope scope(List<String> workers, Instant from, Instant to) {
        var params = new ArrayList<Object>(workers);
        params.add(Timestamp.from(from)); params.add(Timestamp.from(to));
        return new Scope(workerScope(workers) + " AND occurred_at >= ? AND occurred_at < ?", params);
    }

    private static String workerScope(List<String> workers) {
        return "worker_id IN (" + String.join(",", Collections.nCopies(workers.size(), "?")) + ")";
    }
}
