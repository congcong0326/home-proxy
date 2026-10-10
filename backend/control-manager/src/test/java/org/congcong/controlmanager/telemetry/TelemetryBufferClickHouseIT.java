package org.congcong.controlmanager.telemetry;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.congcong.common.telemetry.*;
import org.congcong.controlmanager.clickhouse.ClickHouseJdbcClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real JDBC batch verification. Keeps the synthetic facts; never deletes existing data. */
@EnabledIfEnvironmentVariable(named = "TELEMETRY_CLICKHOUSE_URL", matches = ".+")
class TelemetryBufferClickHouseIT {
    @Test
    void allFourTablesAcceptMemoryAckThenRealBatchFlushIncludingPublicGeoAndLargeCounters() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TELEMETRY_CLICKHOUSE_URL"));
        config.setUsername(System.getenv().getOrDefault("TELEMETRY_CLICKHOUSE_USERNAME", "default"));
        config.setPassword(System.getenv().getOrDefault("TELEMETRY_CLICKHOUSE_PASSWORD", ""));
        config.setMaximumPoolSize(2);
        String worker = "SYNTHETIC_BATCH_FLUSH_" + UUID.randomUUID();
        try (var source = new HikariDataSource(config)) {
            var client = new ClickHouseJdbcClient(source);
            var geo = new CredentialGeoAnalysis();
            try {
                var now = new AtomicLong();
                var store = new TelemetryStore(client, new TelemetryProperties(), geo, now::get);
                store.requireQueryAvailable();
                var batch = new TelemetryBatchV1();
                batch.setBatchId(UUID.randomUUID().toString());
                batch.setSessions(List.of(session(), session()));
                batch.setDnsQueries(List.of(dns(), dns()));
                batch.setEvents(List.of(event(), event()));
                batch.setCredentialAuth(List.of(auth(), auth()));
                assertTrue(store.ingest(worker, batch).isPersisted());
                for (var schema : TelemetrySchema.values()) assertEquals(0, count(client, schema, worker));
                now.set(120_000_000_000L);
                store.flushDue();
                assertEquals(0, store.bufferStatus().get("queueDepth"));
                for (var schema : TelemetrySchema.values()) assertEquals(2, count(client, schema, worker));
                var auth = store.query(TelemetrySchema.AUTH, worker, Instant.now().minusSeconds(60), Instant.now(), 100, 0, null);
                var analysis = (java.util.Map<?, ?>) auth.get(0).get("analysis");
                assertEquals("RESOLVED", analysis.get("geoLookupStatus"));
                assertNotNull(analysis.get("sourceCountry"));
                var sessions = store.query(TelemetrySchema.SESSIONS, worker, Instant.now().minusSeconds(60), Instant.now(), 100, 0, null);
                assertEquals("9007199254740993", sessions.get(0).get("uploadBytes"));
                store.stop();
            } finally { geo.close(); }
        }
    }

    private long count(ClickHouseJdbcClient client, TelemetrySchema schema, String worker) {
        return ((Number) client.query("SELECT count() AS n FROM " + schema.table + " FINAL WHERE worker_id = ?", worker)
                .get(0).get("n")).longValue();
    }

    private <T extends TelemetryRecordV1> T base(T record) {
        record.setRecordId(UUID.randomUUID().toString());
        record.setWorkerInstanceId(UUID.randomUUID().toString());
        record.setOccurredAt(Instant.now());
        record.setClientIp("8.8.8.8");
        return record;
    }

    private SessionLogV1 session() {
        var record = base(new SessionLogV1());
        record.setConnectionId(record.getRecordId());
        record.setStartedAt(record.getOccurredAt().minusSeconds(1)); record.setEndedAt(record.getOccurredAt());
        record.setConnectOutcome(ConnectOutcome.NOT_ATTEMPTED); record.setSessionOutcome(SessionOutcome.CANCELLED);
        record.setUploadBytes(9007199254740993L); record.setDownloadBytes(0L);
        return record;
    }

    private DnsQueryLogV1 dns() {
        var record = base(new DnsQueryLogV1()); record.setQueryId(record.getRecordId()); return record;
    }

    private ProxyEventLogV1 event() {
        var record = base(new ProxyEventLogV1()); record.setEventId(record.getRecordId()); return record;
    }

    private CredentialAuthLogV1 auth() {
        var record = base(new CredentialAuthLogV1()); record.setAuthAttemptId(record.getRecordId()); return record;
    }
}
