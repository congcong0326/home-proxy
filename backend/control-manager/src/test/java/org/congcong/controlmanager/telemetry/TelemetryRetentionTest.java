package org.congcong.controlmanager.telemetry;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.congcong.controlmanager.telemetry.TelemetrySqliteFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Retention runs against real SQLite with small limits and fixed UTC boundaries. */
class TelemetryRetentionTest {
    private static final Instant NOW = Instant.parse("2026-10-31T12:34:56Z");
    private static final Instant TODAY = NOW.truncatedTo(ChronoUnit.DAYS);

    @Test
    void defaultsKeepExactlyNinetyCalendarDaysAcrossAllTablesAndWorkers() throws Exception {
        try (var db = new TelemetrySqliteFixture()) {
            assertEquals(90, db.properties.getRetentionDays());
            assertEquals(100_000_000L, db.properties.getMaxRecords());
            Instant cutoff = TODAY.minus(89, ChronoUnit.DAYS);
            for (String worker : new String[]{"first", "second"}) {
                for (Instant time : new Instant[]{cutoff.minusNanos(1000), cutoff, NOW}) {
                    db.ingest(worker, session(time, time, 0, 0), dns(time, "ANSWERED", true),
                            failedAuth(time, null, "test"));
                }
            }
            db.store.purgeExpired(NOW);
            for (TelemetrySchema schema : TelemetrySchema.values()) {
                assertEquals(4, db.count(schema));
                assertEquals(cutoff, TelemetryJdbcClient.instant(db.client.query(
                        "SELECT MIN(occurred_at) AS first FROM " + schema.table).get(0).get("first")));
            }
        }
    }

    @Test
    void countLimitDeletesWholeOldestDaysAcrossTypesUntilCombinedTotalFits() throws Exception {
        try (var db = new TelemetrySqliteFixture()) {
            db.properties.setMaxRecords(3);
            // No individual table exceeds 3, but the combined total does. Removing one day is insufficient.
            Instant first = TODAY.minus(2, ChronoUnit.DAYS);
            Instant second = first.plus(1, ChronoUnit.DAYS);
            db.ingest("first", session(first, first, 0, 0), dns(first.plusSeconds(86399), "ANSWERED", true));
            db.ingest("second", failedAuth(second, null, "test"), dns(second.plusSeconds(1), "ANSWERED", true),
                    session(NOW, NOW, 0, 0), dns(NOW, "ANSWERED", true));
            db.store.purgeExpired(NOW);
            assertEquals(1, db.count(TelemetrySchema.SESSIONS));
            assertEquals(1, db.count(TelemetrySchema.DNS));
            assertEquals(0, db.count(TelemetrySchema.AUTH));
            // Repeating cleanup below the limit preserves the newer day.
            db.store.purgeExpired(NOW);
            assertEquals(1, db.count(TelemetrySchema.SESSIONS));
            assertEquals(1, db.count(TelemetrySchema.DNS));
        }
    }

    @Test
    void exactlyAtLimitPreservesAllRecordsIncludingTodaysAndCanRemoveTodayOnOverflow() throws Exception {
        try (var db = new TelemetrySqliteFixture()) {
            db.properties.setMaxRecords(2);
            db.ingest("worker", dns(TODAY, "ANSWERED", true), failedAuth(NOW, null, "test"));
            db.store.purgeExpired(NOW);
            assertEquals(1, db.count(TelemetrySchema.DNS));
            assertEquals(1, db.count(TelemetrySchema.AUTH));
            db.ingest("worker", session(NOW, NOW, 0, 0));
            db.store.purgeExpired(NOW);
            for (TelemetrySchema schema : TelemetrySchema.values()) assertEquals(0, db.count(schema));
        }
    }

    @Test
    void expiryRunsBeforeCountAndLongerTableOverrideCannotExtendGlobalRetention() throws Exception {
        try (var db = new TelemetrySqliteFixture()) {
            db.properties.setMaxRecords(2);
            db.properties.setSessionRetentionDays(365);
            Instant old = TODAY.minus(90, ChronoUnit.DAYS);
            db.ingest("worker", session(old, old, 0, 0), dns(NOW, "ANSWERED", true), failedAuth(NOW, null, "test"));
            db.store.purgeExpired(NOW);
            assertEquals(0, db.count(TelemetrySchema.SESSIONS));
            assertEquals(1, db.count(TelemetrySchema.DNS));
            assertEquals(1, db.count(TelemetrySchema.AUTH));
        }
    }

    @Test
    void cleanupContinuesBeyondOneDeleteBatchAndHandlesEmptyDatabase() throws Exception {
        try (var db = new TelemetrySqliteFixture()) {
            db.store.purgeExpired(NOW);
            // These minimal persisted facts are intentionally older than the 90-day cutoff.
            db.client.execute("WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<5001) "
                    + "INSERT INTO dns_query_log_v1 (record_id, worker_id, worker_instance_id, occurred_at, "
                    + "schema_version, received_at, raw_hash, raw_json) "
                    + "SELECT CAST(x AS TEXT), 'worker', 'instance', 0, 1, 0, 'hash', '{}' FROM n");
            db.ingest("worker", dns(NOW, "ANSWERED", true));
            db.store.purgeExpired(NOW);
            assertEquals(1, db.count(TelemetrySchema.DNS));
        }
    }
}
