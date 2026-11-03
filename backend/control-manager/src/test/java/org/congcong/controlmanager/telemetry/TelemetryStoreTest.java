package org.congcong.controlmanager.telemetry;

import org.congcong.common.telemetry.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TelemetryStoreTest {
    private final TelemetryJdbcClient client = mock(TelemetryJdbcClient.class);
    private final TelemetryProperties properties = new TelemetryProperties();
    private final java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong();
    private final TelemetryStore store = new TelemetryStore(client, properties, mock(CredentialGeoAnalysis.class), now::get);

    @Test
    void ackIsReturnedAfterMemoryAdmissionWithoutAnyDatabaseRequest() throws Exception {
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
        verify(client).batchExecute(startsWith("INSERT OR IGNORE INTO proxy_session_log_v1"), params.capture());
        var fields = TelemetrySchema.SESSIONS.fields().stream().map(TelemetrySchema::column).toList();
        List<?> row = (List<?>) params.getValue().get(0);
        assertEquals("authenticated-node", row.get(fields.indexOf("worker_id")));
        assertEquals(0L, row.get(fields.indexOf("upload_bytes")));
        verify(client, never()).query(startsWith("SELECT"), any(Object[].class));
    }

    @Test
    void flushesExactlyOneBatchWhenSessionQueueReachesDefault1000() throws Exception {
        for (int i = 0; i < 10; i++) {
            var batch = batch();
            var records = new java.util.ArrayList<SessionLogV1>();
            for (int j = 0; j < 100; j++) records.add(batch().getSessions().get(0));
            batch.setSessions(records);
            store.ingest("node", batch);
            if (i < 9) {
                store.flushDue();
                verifyNoInteractions(client);
            }
        }
        store.flushDue();
        verify(client).batchExecute(startsWith("INSERT OR IGNORE INTO proxy_session_log_v1"), argThat(rows -> rows.size() == 1000));
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
        verify(client).batchExecute(startsWith("INSERT OR IGNORE INTO proxy_session_log_v1"), argThat(rows -> rows.size() == 2));
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
        verify(client).batchExecute(startsWith("INSERT OR IGNORE INTO proxy_session_log_v1"), argThat(rows -> rows.size() == 2));
        verify(client, never()).batchExecute(startsWith("INSERT OR IGNORE INTO credential_auth_log_v1"), anyList());
        now.set(180_000_000_000L);
        store.flushDue();
        verify(client).batchExecute(startsWith("INSERT OR IGNORE INTO credential_auth_log_v1"), argThat(rows -> rows.size() == 1));
    }

    @Test
    void failedFlushRetainsRecordsAndReceivedAtUntilSuccessfulRetry() throws Exception {
        store.ingest("node", batch());
        now.set(120_000_000_000L);
        when(client.batchExecute(startsWith("INSERT OR IGNORE INTO"), anyList()))
                .thenThrow(new RuntimeException("SQLITE_BUSY")).thenReturn(new int[]{1});
        store.flushDue();
        assertEquals(1, store.bufferStatus().get("queueDepth"));
        store.flushDue();
        var params = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(client, times(2)).batchExecute(startsWith("INSERT OR IGNORE INTO"), params.capture());
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
        verify(client).batchExecute(startsWith("INSERT OR IGNORE INTO proxy_session_log_v1"), argThat(rows -> rows.size() == 1));
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
        record.setRecordId(UUID.randomUUID().toString());
        record.setAuthAttemptId(record.getRecordId());
        record.setAuthOutcome(AuthOutcome.FAILURE);
        record.setWorkerInstanceId(UUID.randomUUID().toString());
        record.setOccurredAt(Instant.now());
        var batch = new TelemetryBatchV1();
        batch.setBatchId(UUID.randomUUID().toString());
        batch.setCredentialAuth(List.of(record));
        return batch;
    }

    @Test
    void queryRequiresBoundedWorkerAndTimeAndReadsDeduplicatedFacts() {
        properties.setQueryEnabled(true);
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class, () -> store.query(TelemetrySchema.SESSIONS, "", now.minusSeconds(60), now, 100, 0, null));
        assertThrows(IllegalArgumentException.class, () -> store.query(TelemetrySchema.SESSIONS, "node", now.minusSeconds(60), now, 201, 0, null));
        assertThrows(IllegalArgumentException.class, () -> store.query(TelemetrySchema.SESSIONS, "node", now.minusSeconds(31 * 86400L + 1), now, 100, 0, null));
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of(Map.of("raw_json", "{\"downloadBytes\":\"9007199254740993\",\"dnsDurationUs\":null,\"rateLimitWaitUs\":null,\"rateLimitPolicyId\":null}", "received_at", 1_791_507_600_000_000L)));
        var rows = store.query(TelemetrySchema.SESSIONS, "node", now.minusSeconds(60), now, 100, 0, null);
        assertEquals("9007199254740993", rows.get(0).get("downloadBytes"));
        assertNull(rows.get(0).get("dnsDurationUs"));
        assertFalse(rows.get(0).containsKey("rateLimitWaitUs"));
        assertFalse(rows.get(0).containsKey("rateLimitPolicyId"));
        assertEquals("2026-10-09T01:00:00Z", rows.get(0).get("receivedAt"));
        verify(client).query(contains("FROM proxy_session_log_v1 WHERE worker_id = ?"), any(Object[].class));
    }

    @Test
    void schemasOnlyCreateIndependentTablesAndDoNotConvertUnknownToZero() {
        for (TelemetrySchema schema : TelemetrySchema.values()) {
            String ddl = schema.ddl();
            assertTrue(ddl.startsWith("CREATE TABLE IF NOT EXISTS " + schema.table + " ("));
            assertTrue(ddl.contains("occurred_at INTEGER NOT NULL"));
            assertTrue(ddl.contains("received_at INTEGER NOT NULL"));
            assertFalse(ddl.contains("DROP"));
            assertTrue(schema.indexes().get(0).startsWith("CREATE UNIQUE INDEX IF NOT EXISTS ux_" + schema.table + "_fact"));
        }
        // Unknown values stay NULL rather than being coerced to 0.
        assertTrue(TelemetrySchema.SESSIONS.ddl().contains("dns_duration_us INTEGER,"));
        assertTrue(TelemetrySchema.DNS.ddl().contains("answer_ips TEXT"));
    }
    private static final com.fasterxml.jackson.databind.ObjectMapper WIRE = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    /** Wire JSON as an older worker sends it: removed fields, diagnostic events and non-failure auth records. */
    private TelemetryBatchV1 legacy(boolean session, boolean event, String authOutcome) throws Exception {
        String now = Instant.now().toString();
        String id = UUID.randomUUID().toString();
        String instance = UUID.randomUUID().toString();
        String common = "\"workerInstanceId\":\"" + instance + "\",\"occurredAt\":\"" + now + "\",\"configVersion\":\"h\","
                + "\"clientIpSource\":\"TRANSPORT_PEER\",\"transportPeerIp\":\"192.0.2.7\",\"someFutureField\":1";
        List<String> parts = new java.util.ArrayList<>();
        if (session) {
            parts.add("\"sessions\":[{\"recordId\":\"" + id + "\",\"connectionId\":\"" + id + "\"," + common
                    + ",\"startedAt\":\"" + now + "\",\"endedAt\":\"" + now + "\",\"connectOutcome\":\"CANCELLED\","
                    + "\"sessionOutcome\":\"CANCELLED\",\"uploadBytes\":\"0\",\"downloadBytes\":\"0\","
                    + "\"closeReason\":\"CHANNEL_CLOSED\",\"routeSelectionDurationUs\":\"3\"}]");
        }
        if (event) {
            parts.add("\"events\":[{\"recordId\":\"" + id + "\",\"eventId\":\"" + id + "\",\"eventType\":\"CONNECT_RESULT\"," + common + "}]");
        }
        if (authOutcome != null) {
            String auth = UUID.randomUUID().toString();
            parts.add("\"credentialAuth\":[{\"recordId\":\"" + auth + "\",\"authAttemptId\":\"" + auth + "\"," + common
                    + ",\"authOutcome\":\"" + authOutcome + "\",\"verifiedUsername\":\"alice\"}]");
        }
        parts.add("\"batchId\":\"" + UUID.randomUUID() + "\"");
        return WIRE.readValue("{" + String.join(",", parts) + "}", TelemetryBatchV1.class);
    }

    private int queued(String table) {
        return ((Map<?, ?>) store.bufferStatus().get("tables")).get(table) instanceof Integer n ? n : -1;
    }

    @Test
    void legacyBatchWithRemovedFieldsAndEventsIsAcceptedAndOnlyKnownFactsAreStored() throws Exception {
        var batch = legacy(true, true, "FAILURE");
        assertEquals(1, batch.getEvents().size());
        assertEquals(2, batch.recordCount());
        assertTrue(store.ingest("node", batch).isPersisted());
        assertEquals(1, queued("sessions"));
        assertEquals(1, queued("credential-auth"));
        assertEquals(2, store.bufferStatus().get("queueDepth"));
    }

    @Test
    void batchWithOnlyEventsIsAcknowledgedWithoutBuffering() throws Exception {
        var batch = legacy(false, true, null);
        assertEquals(0, batch.recordCount());
        var ack = store.ingest("node", batch);
        assertTrue(ack.isPersisted());
        assertEquals(batch.getBatchId(), ack.getBatchId());
        assertEquals(0, store.bufferStatus().get("queueDepth"));
        verifyNoInteractions(client);
    }

    @Test
    void everyAuthOutcomeIsStored() throws Exception {
        for (String outcome : List.of("SUCCESS", "CHALLENGE", "CANCELLED", "FAILURE", "DENIED", "MALFORMED", "ERROR")) {
            assertTrue(store.ingest("node", legacy(false, false, outcome)).isPersisted(), outcome);
        }
        assertEquals(7, queued("credential-auth"));
    }

    @Test
    void emptyBatchStillFailsAndLimitsApplyToTheOriginalBatch() throws Exception {
        var empty = new TelemetryBatchV1();
        empty.setBatchId(UUID.randomUUID().toString());
        assertThrows(IllegalArgumentException.class, () -> store.ingest("node", empty));
        properties.setMaxBatchRecords(1);
        assertThrows(IllegalArgumentException.class, () -> store.ingest("node", legacy(true, true, null)));
    }

    @Test
    void retentionDaysMapToTheirOwnTables() {
        properties.setSessionRetentionDays(11);
        properties.setDnsRetentionDays(22);
        when(client.query(startsWith("SELECT SUM(n)"), any(Object[].class)))
                .thenReturn(List.of(Map.of("total", 0L)));
        Instant now = Instant.parse("2026-10-31T12:34:56Z");
        store.purgeExpired(now);
        var cutoffs = new java.util.HashMap<String, Instant>();
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        var params = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(client, atLeastOnce()).execute(sql.capture(), params.capture());
        for (int i = 0; i < sql.getAllValues().size(); i++) {
            String statement = sql.getAllValues().get(i);
            if (statement.startsWith("DELETE FROM ")) {
                cutoffs.put(statement.substring("DELETE FROM ".length(), statement.indexOf(' ', "DELETE FROM ".length())),
                        (Instant) params.getAllValues().get(i)[0]);
            }
        }
        assertEquals(java.util.Set.of("proxy_session_log_v1", "dns_query_log_v1", "credential_auth_log_v1"), cutoffs.keySet());
        assertEquals(Instant.parse("2026-10-21T00:00:00Z"), cutoffs.get("proxy_session_log_v1"));
        assertEquals(Instant.parse("2026-10-10T00:00:00Z"), cutoffs.get("dns_query_log_v1"));
        assertEquals(Instant.parse("2026-08-03T00:00:00Z"), cutoffs.get("credential_auth_log_v1"));
    }

    private TelemetryBatchV1 batch() {
        var record = new SessionLogV1();
        record.setRecordId(UUID.randomUUID().toString());
        record.setConnectionId(record.getRecordId());
        record.setWorkerId("spoofed-node");
        record.setWorkerInstanceId(UUID.randomUUID().toString());
        record.setOccurredAt(Instant.now());
        record.setStartedAt(record.getOccurredAt().minusSeconds(1));
        record.setEndedAt(record.getOccurredAt());
        record.setConnectOutcome(ConnectOutcome.NOT_ATTEMPTED);
        record.setSessionOutcome(SessionOutcome.CANCELLED);
        record.setUploadBytes(0L);
        record.setDownloadBytes(0L);
        var batch = new TelemetryBatchV1();
        batch.setBatchId(UUID.randomUUID().toString());
        batch.setSessions(List.of(record));
        return batch;
    }
}
