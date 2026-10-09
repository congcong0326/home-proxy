package org.congcong.proxyworker.telemetry;

import org.congcong.common.telemetry.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryBufferTest {
    @Test
    void lostAckKeepsOriginalSnapshotAndBatchUntilMatchingDurableAck() throws Exception {
        var buffer = new TelemetryBuffer(2, 1000000);
        var record = record();
        String id = record.getRecordId();
        Instant occurred = record.getOccurredAt();
        assertTrue(buffer.offer(record));
        record.setDownloadBytes(0L);
        var first = buffer.peek();
        assertEquals(id, first.getSessions().get(0).getRecordId());
        assertEquals(occurred, first.getSessions().get(0).getOccurredAt());
        assertEquals(9007199254740993L, first.getSessions().get(0).getDownloadBytes());
        first.getSessions().get(0).setDownloadBytes(0L);
        var retry = buffer.peek();
        assertEquals(first.getBatchId(), retry.getBatchId());
        assertEquals(9007199254740993L, retry.getSessions().get(0).getDownloadBytes());
        assertFalse(buffer.acknowledge(ack(retry.getBatchId(), false)));
        assertFalse(buffer.acknowledge(ack(UUID.randomUUID().toString(), true)));
        assertEquals(1, buffer.depth());
        assertTrue(buffer.acknowledge(ack(retry.getBatchId(), true)));
        assertNull(buffer.peek()); assertEquals(0, buffer.bytesUsed());
    }

    @Test
    void pendingBatchStillConsumesCapacityAndOverflowPreservesExistingFacts() throws Exception {
        var buffer = new TelemetryBuffer(2, 1000000);
        assertTrue(buffer.offer(record()));
        var pending = buffer.peek();
        assertTrue(buffer.offer(record()));
        assertEquals(2, buffer.depth());
        assertFalse(buffer.offer(record()));
        assertEquals(pending.getBatchId(), buffer.peek().getBatchId());
        assertTrue(buffer.acknowledge(ack(pending.getBatchId(), true)));
        assertEquals(1, buffer.depth());
        assertNotEquals(pending.getBatchId(), buffer.peek().getBatchId());
    }

    @Test
    void byteLimitAndOversizedFactCannotCreateEmptyPendingBatch() throws Exception {
        var limited = new TelemetryBuffer(10, 1);
        assertFalse(limited.offer(record()));
        assertNull(limited.peek()); assertEquals(0, limited.bytesUsed());
        var buffer = new TelemetryBuffer(10, 1000000);
        var oversized = record(); oversized.setErrorMessage("x".repeat(TelemetryBuffer.MAX_BATCH_BYTES));
        assertFalse(buffer.offer(oversized));
        assertNull(buffer.peek());
        assertTrue(buffer.offer(record()));
        assertEquals(1, buffer.peek().recordCount());
    }

    @Test
    void recordAndByteLimitsApplyToBatchAndAckReleasesOnlyThatBatch() throws Exception {
        var buffer = new TelemetryBuffer(101, 1000000);
        for (int i = 0; i < 101; i++) assertTrue(buffer.offer(record()));
        var first = buffer.peek();
        assertEquals(100, first.recordCount());
        assertEquals(101, buffer.depth());
        assertTrue(buffer.acknowledge(ack(first.getBatchId(), true)));
        assertEquals(1, buffer.peek().recordCount());
    }

    @Test
    void newWorkerBufferStartsEmptyAndShutdownDiscardsUnconfirmedFacts() throws Exception {
        var buffer = new TelemetryBuffer(10, 1000000);
        assertTrue(buffer.offer(record()));
        buffer.peek();
        assertNull(new TelemetryBuffer(10, 1000000).peek());
        assertEquals(1, buffer.clear());
        assertNull(buffer.peek()); assertEquals(0, buffer.depth()); assertEquals(0, buffer.bytesUsed());
    }

    @Test
    void unsupportedAckVersionCannotReleasePendingFacts() throws Exception {
        var buffer = new TelemetryBuffer(10, 1000000);
        assertTrue(buffer.offer(record()));
        var pending = buffer.peek();
        var ack = ack(pending.getBatchId(), true); ack.setProtocolVersion(2);
        assertFalse(buffer.acknowledge(ack));
        assertEquals(pending.getBatchId(), buffer.peek().getBatchId());
    }
    private TelemetryAckV1 ack(String id, boolean persisted) {
        var ack = new TelemetryAckV1(); ack.setBatchId(id); ack.setPersisted(persisted); return ack;
    }
    private SessionLogV1 record() {
        var record = new SessionLogV1(); record.setRecordId(UUID.randomUUID().toString()); record.setConnectionId(record.getRecordId());
        record.setWorkerInstanceId(UUID.randomUUID().toString()); record.setOccurredAt(Instant.now());
        record.setDownloadBytes(9007199254740993L); return record;
    }
}
