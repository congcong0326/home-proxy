package org.congcong.controlmanager.service.mail.inspection;

import org.congcong.common.telemetry.ConnectOutcome;
import org.congcong.common.telemetry.SessionOutcome;
import org.congcong.common.telemetry.TelemetryRecordV1;
import org.congcong.common.telemetry.TrafficCategory;
import org.congcong.controlmanager.telemetry.TelemetrySqliteFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.congcong.controlmanager.telemetry.TelemetrySqliteFixture.dns;
import static org.congcong.controlmanager.telemetry.TelemetrySqliteFixture.session;
import static org.junit.jupiter.api.Assertions.*;

class NetworkInspectionServiceTest {
    private final Instant from = Instant.parse("2026-10-09T01:00:00Z"), to = Instant.parse("2026-10-10T01:00:00Z");
    private TelemetrySqliteFixture db;
    private NetworkInspectionService service;

    @BeforeEach
    void setUp() throws Exception {
        db = new TelemetrySqliteFixture();
        service = new NetworkInspectionService(db.client, db.store, new InspectionProperties());
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    private Instant at(int seconds) {
        return from.plusSeconds(3600 + seconds);
    }

    @Test
    void computesFailureRateWithoutBlocksAndCancellationsAndExactP95() throws Exception {
        var records = new ArrayList<TelemetryRecordV1>();
        // 9 successes with setup times 1..9 ms, 1 failure, 2 blocked, 1 cancelled, 1 transfer error.
        for (int i = 1; i <= 9; i++) {
            var ok = session(at(i), at(i + 1), 1, 1);
            ok.setChannelSetupDurationUs(i * 1000L);
            if (i == 9) ok.setSessionOutcome(SessionOutcome.ERROR_END);
            records.add(ok);
        }
        records.add(outcome(at(20), ConnectOutcome.FAILED));
        records.add(outcome(at(21), ConnectOutcome.BLOCKED));
        records.add(outcome(at(22), ConnectOutcome.BLOCKED));
        records.add(outcome(at(23), ConnectOutcome.CANCELLED));
        db.ingest("default", records.toArray(TelemetryRecordV1[]::new));

        var report = service.connections(List.of("default"), from, to, ZoneId.of("UTC"));
        var fields = report.items().get(0).fields();
        assertEquals("13", fields.get("结束连接记录"));
        assertEquals("9 / 1", fields.get("建连成功 / 失败"));
        assertEquals("10.00%", fields.get("建连失败率"));
        assertEquals("2 / 1", fields.get("策略阻断 / 客户端取消"));
        assertEquals("1", fields.get("建连后传输错误"));
        // quantileExact: index floor(0.95 * 9) = 8 of the sorted samples -> 9 ms.
        assertEquals("9.000 ms（有效样本 9）", fields.get("成功建连耗时 P95"));
        assertTrue(fields.get("历史覆盖").contains("本窗口历史不足"));
        assertEquals("主要错误：default / UNKNOWN", report.items().get(1).title());
    }

    @Test
    void missingTimingStaysUnmeasured() throws Exception {
        db.ingest("default", session(at(1), at(2), 1, 1));
        var fields = service.connections(List.of("default"), from, to, ZoneId.of("UTC")).items().get(0).fields();
        assertEquals("未测量", fields.get("成功建连耗时 P95"));
    }

    @Test
    void countsOnlyExternalTrafficAndDoesNotCombineChainedNodes() throws Exception {
        var external = session(at(1), at(2), 100, 900);
        var unknown = session(at(3), at(4), 2, 1);
        unknown.setTrafficCategory(null);
        var internal = session(at(5), at(6), 50, 50);
        internal.setTrafficCategory(TrafficCategory.INTERNAL_REWRITE);
        db.ingest("default", external, unknown, internal);
        db.ingest("edge", session(at(1), at(2), 10, 10));

        var report = service.traffic(List.of("edge", "default"), from, to, ZoneId.of("UTC"));
        assertEquals("主网关总览 default", report.items().get(0).title());
        var fields = report.items().get(0).fields();
        assertEquals("1000.00 B（1000 B）", fields.get("外网合计"));
        assertEquals("1", fields.get("外网结束连接数"));
        assertEquals("3.00 B（3 B）", fields.get("未分类流量（不计外网）"));
        assertEquals(2, report.items().size());
        assertTrue(report.notes().stream().anyMatch(note -> note.contains("不跨节点相加")));
    }

    @Test
    void separatesDnsAnswerModesAndReportsProcessingTimeWithFullDenominator() throws Exception {
        var records = new ArrayList<TelemetryRecordV1>();
        for (int i = 1; i <= 9; i++) {
            var answered = dns(at(i), "ANSWERED", true);
            answered.setAnswerMode("REAL");
            answered.setDurationUs(i * 100L);
            records.add(answered);
        }
        var error = dns(at(10), "ERROR", false);
        error.setAnswerMode("REAL");
        error.setDurationUs(25100L);
        records.add(error);
        var fake = dns(at(11), "ANSWERED", true);
        fake.setAnswerMode("FAKE_IP");
        fake.setDurationUs(100L);
        records.add(fake);
        var proxy = dns(at(12), "ERROR", false);
        proxy.setQueryOrigin("PROXY_RESOLVE");
        records.add(proxy);
        db.ingest("default", records.toArray(TelemetryRecordV1[]::new));

        var report = service.dns(List.of("default"), from, to, ZoneId.of("UTC"));
        assertEquals(2, report.items().size());
        var fakeIp = report.items().get(0).fields();
        assertEquals("1", fakeIp.get("客户端查询量"));
        var real = report.items().get(1).fields();
        assertEquals("10", real.get("客户端查询量"));
        assertEquals("10.00%", real.get("服务错误率"));
        // floor(0.95 * 10) = 9 -> the slowest sample.
        assertEquals("25.100 ms（有效样本 10）", real.get("处理耗时 P95"));
        assertTrue(report.notes().stream().anyMatch(note -> note.contains("不等于上游解析耗时")));
    }

    private static TelemetryRecordV1 outcome(Instant time, ConnectOutcome outcome) {
        var record = session(time, time, 0, 0);
        record.setConnectedAt(null);
        record.setConnectOutcome(outcome);
        record.setSessionOutcome(outcome == ConnectOutcome.FAILED ? SessionOutcome.ERROR_END
                : outcome == ConnectOutcome.BLOCKED ? SessionOutcome.BLOCKED : SessionOutcome.CANCELLED);
        return record;
    }
}
