package org.congcong.controlmanager.telemetry;

import org.congcong.common.telemetry.*;
import org.congcong.controlmanager.clickhouse.ClickHouseJdbcClient;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TelemetryStoreTest {
    private final ClickHouseJdbcClient client = mock(ClickHouseJdbcClient.class);
    private final TelemetryProperties properties = new TelemetryProperties();
    private final java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong();
    private final TelemetryStore store = new TelemetryStore(client, properties, mock(CredentialGeoAnalysis.class), now::get);

    @Test
    void ackIsReturnedAfterMemoryAdmissionWithoutAnyCkRequest() throws Exception {
        var batch = batch();
        var ack = store.ingest("authenticated-node", batch);
        assertTrue(ack.isPersisted());
        assertEquals(batch.getBatchId(), ack.getBatchId());
        assertEquals(1, store.bufferStatus().get("queueDepth"));
        verifyNoInteractions(client);
        batch.getSessions().get(0).setWorkerId("changed-after-ack");
        batch.getSessions().get(0).setUploadBytes(123L);
        now.set(120_000_000_000L);
        store.flushDue();
        var params = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(client).batchExecute(startsWith("INSERT INTO telemetry.proxy_session_log_v1"), params.capture());
        var fields = TelemetrySchema.SESSIONS.fields().stream().map(TelemetrySchema::column).toList();
        List<?> row = (List<?>) params.getValue().get(0);
        assertEquals("authenticated-node", row.get(fields.indexOf("worker_id")));
        assertEquals(0L, row.get(fields.indexOf("upload_bytes")));
        verify(client, never()).query(anyString(), any(Object[].class));
    }

    @Test
    void flushesExactlyOneBatchWhenSessionQueueReachesDefault1000() throws Exception {
        for (int i = 0; i < 10; i++) {
            var batch = batch();
            var records = new java.util.ArrayList<SessionLogV1>();
            for (int j = 0; j < 100; j++) records.add(batch().getSessions().get(0));
            batch.setSessions(records);
            store.ingest("node", batch);
            if (i < 9) { store.flushDue(); verifyNoInteractions(client); }
        }
        store.flushDue();
        verify(client).batchExecute(startsWith("INSERT INTO telemetry.proxy_session_log_v1"), argThat(rows -> rows.size() == 1000));
        assertEquals(0, store.bufferStatus().get("queueDepth"));
    }

    @Test
    void flushesAtTwoMinutesFromOldestRecordEvenWhenMoreRecordsArrive() throws Exception {
        store.ingest("node", batch());
        now.set(119_999_000_000L);
        store.ingest("node", batch());
        store.flushDue();
        verifyNoInteractions(client);
        now.set(120_000_000_000L);
        store.flushDue();
        verify(client).batchExecute(startsWith("INSERT INTO telemetry.proxy_session_log_v1"), argThat(rows -> rows.size() == 2));
        assertEquals(0, store.bufferStatus().get("queueDepth"));
    }

    @Test
    void eachTableHasIndependentThresholdAndClock() throws Exception {
        properties.setFlushBatchSize(2);
        store.ingest("node", batch());
        now.set(60_000_000_000L);
        var authBatch = authBatch();
        store.ingest("node", authBatch);
        store.ingest("node", batch());
        store.flushDue();
        verify(client).batchExecute(startsWith("INSERT INTO telemetry.proxy_session_log_v1"), argThat(rows -> rows.size() == 2));
        verify(client, never()).batchExecute(startsWith("INSERT INTO telemetry.credential_auth_log_v1"), anyList());
        now.set(180_000_000_000L);
        store.flushDue();
        verify(client).batchExecute(startsWith("INSERT INTO telemetry.credential_auth_log_v1"), argThat(rows -> rows.size() == 1));
    }

    @Test
    void failedFlushRetainsRecordsAndReceivedAtUntilSuccessfulRetry() throws Exception {
        store.ingest("node", batch());
        now.set(120_000_000_000L);
        when(client.batchExecute(startsWith("INSERT INTO"), anyList()))
                .thenThrow(new RuntimeException("CK unavailable")).thenReturn(new int[]{1});
        store.flushDue();
        assertEquals(1, store.bufferStatus().get("queueDepth"));
        store.flushDue();
        var params = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(client, times(2)).batchExecute(startsWith("INSERT INTO"), params.capture());
        assertEquals(params.getAllValues().get(0), params.getAllValues().get(1));
        assertEquals(0, store.bufferStatus().get("queueDepth"));
        assertEquals(0L, store.bufferStatus().get("bufferBytes"));
    }

    @Test
    void invalidBatchAndCapacityOverflowAreRejectedAtomically() throws Exception {
        var invalid = batch();
        invalid.setSessions(List.of(invalid.getSessions().get(0), batch().getSessions().get(0)));
        invalid.getSessions().get(1).setUploadBytes(-1L);
        assertThrows(IllegalArgumentException.class, () -> store.ingest("node", invalid));
        assertEquals(0, store.bufferStatus().get("queueDepth"));
        properties.setBufferCapacity(1);
        store.ingest("node", batch());
        assertThrows(IllegalStateException.class, () -> store.ingest("node", batch()));
        assertEquals(1, store.bufferStatus().get("queueDepth"));
        verifyNoInteractions(client);
    }

    @Test
    void byteLimitRejectsWholeBatchAndGracefulShutdownFlushesSmallQueues() throws Exception {
        properties.setBufferMaxBytes(1);
        assertThrows(IllegalStateException.class, () -> store.ingest("node", batch()));
        assertEquals(0, store.bufferStatus().get("queueDepth"));
        properties.setBufferMaxBytes(67108864);
        store.ingest("node", batch());
        store.stop();
        verify(client).batchExecute(startsWith("INSERT INTO telemetry.proxy_session_log_v1"), argThat(rows -> rows.size() == 1));
        assertThrows(IllegalStateException.class, () -> store.ingest("node", batch()));
    }

    @Test
    void disabledIngestNeverTouchesOldOrNewTables() {
        properties.setIngestEnabled(false);
        assertThrows(IllegalStateException.class, () -> store.ingest("node", batch()));
        verifyNoInteractions(client);
    }

    private TelemetryBatchV1 authBatch() {
        var record = new CredentialAuthLogV1();
        record.setRecordId(UUID.randomUUID().toString()); record.setAuthAttemptId(record.getRecordId());
        record.setWorkerInstanceId(UUID.randomUUID().toString()); record.setOccurredAt(Instant.now());
        var batch = new TelemetryBatchV1(); batch.setBatchId(UUID.randomUUID().toString()); batch.setCredentialAuth(List.of(record));
        return batch;
    }

    @Test
    void queryRequiresBoundedWorkerAndTimeAndReadsDeduplicatedFacts() {
        properties.setQueryEnabled(true);
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class, () -> store.query(TelemetrySchema.SESSIONS, "", now.minusSeconds(60), now, 100, 0, null));
        assertThrows(IllegalArgumentException.class, () -> store.query(TelemetrySchema.SESSIONS, "node", now.minusSeconds(60), now, 201, 0, null));
        assertThrows(IllegalArgumentException.class, () -> store.query(TelemetrySchema.SESSIONS, "node", now.minusSeconds(31 * 86400L + 1), now, 100, 0, null));
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of(Map.of("raw_json", "{\"downloadBytes\":\"9007199254740993\",\"dnsDurationUs\":null,\"rateLimitWaitUs\":null,\"rateLimitPolicyId\":null}", "received_at", "2026-10-09 01:00:00")));
        var rows = store.query(TelemetrySchema.SESSIONS, "node", now.minusSeconds(60), now, 100, 0, null);
        assertEquals("9007199254740993", rows.get(0).get("downloadBytes"));
        assertNull(rows.get(0).get("dnsDurationUs"));
        assertFalse(rows.get(0).containsKey("rateLimitWaitUs"));
        assertFalse(rows.get(0).containsKey("rateLimitPolicyId"));
        verify(client).query(contains(" FINAL WHERE worker_id = ?"), any(Object[].class));
    }

    @Test
    void schemasOnlyCreateIndependentTablesAndDoNotConvertUnknownToZero() {
        for (TelemetrySchema schema : TelemetrySchema.values()) {
            String ddl = schema.ddl();
            assertTrue(ddl.startsWith("CREATE TABLE IF NOT EXISTS telemetry."));
            assertTrue(ddl.contains("DateTime64(6, 'UTC')"));
            assertFalse(ddl.contains("default.")); assertFalse(ddl.contains("DROP")); assertFalse(ddl.contains("TTL"));
        }
        assertTrue(TelemetrySchema.SESSIONS.ddl().contains("dns_duration_us Nullable(UInt64)"));
    }
    private TelemetryBatchV1 batch() {
        var record = new SessionLogV1(); record.setRecordId(UUID.randomUUID().toString()); record.setConnectionId(record.getRecordId());
        record.setWorkerId("spoofed-node"); record.setWorkerInstanceId(UUID.randomUUID().toString()); record.setOccurredAt(Instant.now());
        record.setStartedAt(record.getOccurredAt().minusSeconds(1)); record.setEndedAt(record.getOccurredAt());
        record.setConnectOutcome(ConnectOutcome.NOT_ATTEMPTED); record.setSessionOutcome(SessionOutcome.CANCELLED);
        record.setUploadBytes(0L); record.setDownloadBytes(0L);
        var batch = new TelemetryBatchV1(); batch.setBatchId(UUID.randomUUID().toString()); batch.setSessions(List.of(record)); return batch;
    }
}
