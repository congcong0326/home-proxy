package org.congcong.controlmanager.service.mail.inspection;

import org.congcong.controlmanager.clickhouse.ClickHouseJdbcClient;
import org.congcong.controlmanager.telemetry.TelemetryStore;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NetworkInspectionServiceTest {
    private final ClickHouseJdbcClient client = mock(ClickHouseJdbcClient.class);
    private final TelemetryStore store = mock(TelemetryStore.class);
    private final NetworkInspectionService service = new NetworkInspectionService(client, store, new InspectionProperties());
    private final Instant from = Instant.parse("2026-10-09T01:00:00Z"), to = Instant.parse("2026-10-10T01:00:00Z");

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) row.put(values[i].toString(), values[i + 1]);
        return row;
    }

    @Test
    void computesFailureRateWithoutBlocksAndCancellationsAndKeepsMissingTimingUnknown() {
        when(client.query(anyString(), any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.contains("AS first_at")) return List.of(row("worker_id", "default", "first_at", Timestamp.from(from.plusSeconds(3600))));
            if (sql.contains("example_connection")) return List.of();
            assertTrue(sql.contains("FINAL"));
            assertEquals(List.of("default", Timestamp.from(from), Timestamp.from(to)), Arrays.asList((Object[]) call.getRawArguments()[1]));
            return List.of(row("worker_id", "default", "records", "1107", "connected", "90", "failed", "10",
                    "blocked", "1000", "cancelled", "7", "transfer_errors", "3", "timing_samples", "0", "setup_p95", Double.NaN));
        });
        var report = service.connections(List.of("default"), from, to, ZoneId.of("UTC"));
        var fields = report.items().get(0).fields();
        assertEquals("10.00%", fields.get("建连失败率"));
        assertEquals("3", fields.get("建连后传输错误"));
        assertEquals("未测量", fields.get("成功建连耗时 P95"));
        assertTrue(fields.get("历史覆盖").contains("本窗口历史不足"));
    }

    @Test
    void preservesHugeExternalCountersAndDoesNotCombineChainedNodes() {
        when(client.query(anyString(), any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.contains("AS first_at")) return List.of();
            assertTrue(sql.contains("toUInt128")); assertTrue(sql.contains("traffic_category = 'EXTERNAL'"));
            assertFalse(sql.contains("LIMIT"));
            return List.of(row("worker_id", "default", "upload", "18446744073709551620", "download", "10", "external_records", "5", "unknown_bytes", "3"),
                    row("worker_id", "edge", "upload", "18446744073709551620", "download", "10", "external_records", "5", "unknown_bytes", "0"));
        });
        var report = service.traffic(List.of("edge", "default"), from, to, ZoneId.of("UTC"));
        assertEquals("主网关总览 default", report.items().get(0).title());
        assertTrue(report.items().get(0).fields().get("外网合计").contains("18446744073709551630 B"));
        assertEquals(2, report.items().size());
        assertTrue(report.notes().stream().anyMatch(note -> note.contains("不跨节点相加")));
    }

    @Test
    void separatesDnsAnswerModesAndReportsProcessingTimeWithFullDenominator() {
        when(client.query(anyString(), any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            assertTrue(sql.contains("query_origin = 'CLIENT'"));
            if (sql.contains("AS first_at")) return List.of();
            return List.of(row("worker_id", "default", "mode", "REAL", "records", "200", "answered", "90", "errors", "10", "timing_samples", "200", "p95", "25100"),
                    row("worker_id", "default", "mode", "FAKE_IP", "records", "100", "answered", "100", "errors", "0", "timing_samples", "100", "p95", "100"));
        });
        var report = service.dns(List.of("default"), from, to, ZoneId.of("UTC"));
        assertEquals(2, report.items().size());
        assertEquals("10.00%", report.items().get(0).fields().get("服务错误率"));
        assertEquals("25.100 ms（有效样本 200）", report.items().get(0).fields().get("处理耗时 P95"));
        assertTrue(report.notes().stream().anyMatch(note -> note.contains("不等于上游解析耗时")));
    }
}
