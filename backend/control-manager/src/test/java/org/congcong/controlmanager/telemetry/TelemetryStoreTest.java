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
    private final TelemetryStore store = new TelemetryStore(client, properties, mock(CredentialGeoAnalysis.class));

    @Test
    void ackRequiresDurableInsertAndIdentityIsConnectionBound() throws Exception {
        properties.setIngestEnabled(true);
        var batch = batch();
        when(client.query(anyString(), any(), any())).thenReturn(List.of());
        var ack = store.ingest("authenticated-node", batch);
        assertTrue(ack.isPersisted()); assertEquals(batch.getBatchId(), ack.getBatchId());
        assertEquals("authenticated-node", batch.getSessions().get(0).getWorkerId());
        verify(client).execute(startsWith("INSERT INTO telemetry.proxy_session_log_v1"), any(Object[].class));
    }

    @Test
    void storeFailureNeverProducesAckAndReplayRemainsPossible() {
        properties.setIngestEnabled(true);
        when(client.query(anyString(), any(), any())).thenReturn(List.of());
        doThrow(new RuntimeException("CK unavailable")).when(client).execute(startsWith("INSERT INTO"), any(Object[].class));
        assertThrows(RuntimeException.class, () -> store.ingest("node", batch()));
    }

    @Test
    void conflictIsRejectedBeforeAnyBatchInsert() {
        properties.setIngestEnabled(true);
        when(client.query(anyString(), any(), any())).thenReturn(List.of(Map.of("raw_hash", "different-fact")));
        assertThrows(IllegalArgumentException.class, () -> store.ingest("node", batch()));
        verify(client, never()).execute(startsWith("INSERT INTO"), any(Object[].class));
    }

    @Test
    void disabledIngestNeverTouchesOldOrNewTables() {
        assertThrows(IllegalStateException.class, () -> store.ingest("node", batch()));
        verifyNoInteractions(client);
    }

    @Test
    void queryRequiresBoundedWorkerAndTimeAndReadsDeduplicatedFacts() {
        properties.setQueryEnabled(true);
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class, () -> store.query(TelemetrySchema.SESSIONS, "", now.minusSeconds(60), now, 100, 0, null));
        assertThrows(IllegalArgumentException.class, () -> store.query(TelemetrySchema.SESSIONS, "node", now.minusSeconds(60), now, 201, 0, null));
        assertThrows(IllegalArgumentException.class, () -> store.query(TelemetrySchema.SESSIONS, "node", now.minusSeconds(31 * 86400L + 1), now, 100, 0, null));
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of(Map.of("raw_json", "{\"downloadBytes\":\"9007199254740993\",\"dnsDurationUs\":null}", "received_at", "2026-10-09 01:00:00")));
        var rows = store.query(TelemetrySchema.SESSIONS, "node", now.minusSeconds(60), now, 100, 0, null);
        assertEquals("9007199254740993", rows.get(0).get("downloadBytes"));
        assertNull(rows.get(0).get("dnsDurationUs"));
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
