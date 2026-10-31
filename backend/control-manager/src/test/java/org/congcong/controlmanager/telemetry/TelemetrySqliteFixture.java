package org.congcong.controlmanager.telemetry;

import com.zaxxer.hikari.HikariDataSource;
import org.congcong.common.telemetry.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A throwaway telemetry SQLite file; facts are written through the real ingest → buffer → flush path. */
public final class TelemetrySqliteFixture implements AutoCloseable {
    public static final String INSTANCE = UUID.randomUUID().toString();

    public final Path dir;
    public final HikariDataSource dataSource;
    public final TelemetryJdbcClient client;
    public final TelemetryProperties properties = new TelemetryProperties();
    public final CredentialGeoAnalysis geo = mock(CredentialGeoAnalysis.class);
    public final TelemetryStore store;
    private final AtomicLong now = new AtomicLong();

    public TelemetrySqliteFixture() throws IOException {
        dir = Files.createTempDirectory("telemetry-sqlite-test");
        dataSource = (HikariDataSource) new TelemetryDataSourceConfig()
                .telemetryDataSource("jdbc:sqlite:" + dir.resolve("telemetry.db"), 10000, 2);
        client = new TelemetryJdbcClient(dataSource);
        properties.setMaxBatchRecords(1000);
        properties.setMaxBatchBytes(16 * 1024 * 1024);
        when(geo.analyze(any())).thenAnswer(call -> new CredentialAuthAnalysisV1());
        store = new TelemetryStore(client, properties, geo, now::get);
        store.requireQueryAvailable();
    }

    /** Admits one batch for {@code workerId} and flushes it to SQLite. */
    public void ingest(String workerId, TelemetryRecordV1... records) throws Exception {
        var batch = new TelemetryBatchV1();
        batch.setBatchId(UUID.randomUUID().toString());
        var sessions = new ArrayList<SessionLogV1>();
        var dns = new ArrayList<DnsQueryLogV1>();
        var auth = new ArrayList<CredentialAuthLogV1>();
        for (TelemetryRecordV1 record : records) {
            if (record instanceof SessionLogV1 session) sessions.add(session);
            else if (record instanceof DnsQueryLogV1 query) dns.add(query);
            else if (record instanceof CredentialAuthLogV1 attempt) auth.add(attempt);
        }
        batch.setSessions(sessions);
        batch.setDnsQueries(dns);
        batch.setCredentialAuth(auth);
        store.ingest(workerId, batch);
        now.addAndGet(properties.getFlushMaxDelayMs() * 1_000_000L + 1);
        store.flushDue();
    }

    public long count(TelemetrySchema schema) {
        return ((Number) client.query("SELECT COUNT(*) AS n FROM " + schema.table).get(0).get("n")).longValue();
    }

    private static <T extends TelemetryRecordV1> T base(T record, Instant occurredAt) {
        record.setRecordId(UUID.randomUUID().toString());
        record.setWorkerInstanceId(INSTANCE);
        record.setOccurredAt(occurredAt);
        return record;
    }

    /** A CONNECTED, normally ended external session between {@code connected} and {@code ended}. */
    public static SessionLogV1 session(Instant connected, Instant ended, long upload, long download) {
        var record = base(new SessionLogV1(), ended);
        record.setConnectionId(record.getRecordId());
        record.setStartedAt(connected);
        record.setConnectedAt(connected);
        record.setEndedAt(ended);
        record.setConnectOutcome(ConnectOutcome.CONNECTED);
        record.setSessionOutcome(SessionOutcome.NORMAL_END);
        record.setTrafficCategory(TrafficCategory.EXTERNAL);
        record.setUploadBytes(upload);
        record.setDownloadBytes(download);
        return record;
    }

    public static DnsQueryLogV1 dns(Instant occurredAt, String outcome, boolean responseSent) {
        var record = base(new DnsQueryLogV1(), occurredAt);
        record.setQueryId(record.getRecordId());
        record.setQueryOrigin("CLIENT");
        record.setOutcome(outcome);
        record.setResponseSent(responseSent);
        return record;
    }

    public static CredentialAuthLogV1 failedAuth(Instant occurredAt, String connectionId, String attemptedUsername) {
        var record = base(new CredentialAuthLogV1(), occurredAt);
        record.setAuthAttemptId(record.getRecordId());
        record.setConnectionId(connectionId);
        record.setAttemptedUsername(attemptedUsername);
        record.setAuthOutcome(AuthOutcome.FAILURE);
        record.setAuthMethod("PASSWORD");
        return record;
    }

    public static CredentialAuthLogV1 successAuth(Instant occurredAt, String clientIp, Long userId, String username) {
        var record = base(new CredentialAuthLogV1(), occurredAt);
        record.setAuthAttemptId(record.getRecordId());
        record.setConnectionId(UUID.randomUUID().toString());
        record.setClientIp(clientIp);
        record.setAttemptedUsername(username);
        record.setUserId(userId);
        record.setUsername(username);
        record.setAuthOutcome(AuthOutcome.SUCCESS);
        record.setAuthMethod("PASSWORD");
        return record;
    }

    @Override
    public void close() throws IOException {
        dataSource.close();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
