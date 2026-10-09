package org.congcong.proxyworker.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.congcong.common.telemetry.*;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.UUID;

/** Bounded volatile buffer. Pending facts remain charged to capacity until a matching ACK. */
public final class TelemetryBuffer {
    public static final int MAX_BATCH_BYTES = 262144;
    private final int capacity;
    private final long maxBytes;
    private final ArrayDeque<Frozen> queue = new ArrayDeque<>();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private Pending pending;
    private long bufferedBytes;

    public TelemetryBuffer(int capacity, long maxBytes) {
        if (capacity < 1 || maxBytes < 1) throw new IllegalArgumentException("Buffer limits must be positive");
        this.capacity = capacity;
        this.maxBytes = maxBytes;
    }

    public boolean offer(TelemetryRecordV1 record) throws IOException {
        if (!(record instanceof SessionLogV1 || record instanceof DnsQueryLogV1
                || record instanceof ProxyEventLogV1 || record instanceof CredentialAuthLogV1)) return false;
        // Freeze before handing the fact to another thread; callers may reuse/mutate their DTO.
        byte[] json = mapper.writeValueAsBytes(record);
        synchronized (this) {
            if (depth() >= capacity || json.length > MAX_BATCH_BYTES - 1088
                    || bufferedBytes + json.length > maxBytes) return false;
            queue.addLast(new Frozen(json, record.getClass()));
            bufferedBytes += json.length;
            return true;
        }
    }

    public synchronized TelemetryBatchV1 peek() throws IOException {
        if (pending == null && !queue.isEmpty()) {
            TelemetryBatchV1 batch = new TelemetryBatchV1();
            batch.setBatchId(UUID.randomUUID().toString());
            var sessions = new ArrayList<SessionLogV1>();
            var dns = new ArrayList<DnsQueryLogV1>();
            var events = new ArrayList<ProxyEventLogV1>();
            var auth = new ArrayList<CredentialAuthLogV1>();
            batch.setSessions(sessions); batch.setDnsQueries(dns); batch.setEvents(events); batch.setCredentialAuth(auth);
            long recordBytes = 0;
            int wireBytes = 1024;
            while (batch.recordCount() < 100 && !queue.isEmpty()) {
                Frozen next = queue.peekFirst();
                if (wireBytes + next.json.length + 64 > MAX_BATCH_BYTES) break;
                TelemetryRecordV1 fact = mapper.readValue(next.json, next.type);
                if (fact instanceof SessionLogV1 value) sessions.add(value);
                else if (fact instanceof DnsQueryLogV1 value) dns.add(value);
                else if (fact instanceof ProxyEventLogV1 value) events.add(value);
                else if (fact instanceof CredentialAuthLogV1 value) auth.add(value);
                queue.removeFirst();
                wireBytes += next.json.length + 64;
                recordBytes += next.json.length;
            }
            pending = new Pending(batch.getBatchId(), mapper.writeValueAsBytes(batch), batch.recordCount(), recordBytes);
        }
        // Retries use a detached copy, preserving the original batch even if a caller mutates it.
        return pending == null ? null : mapper.readValue(pending.json, TelemetryBatchV1.class);
    }

    public synchronized boolean acknowledge(TelemetryAckV1 ack) {
        if (pending == null || !ack.isPersisted() || ack.getProtocolVersion() != 1
                || !pending.id.equals(ack.getBatchId())) return false;
        bufferedBytes -= pending.recordBytes;
        pending = null;
        return true;
    }

    public synchronized int depth() { return queue.size() + (pending == null ? 0 : pending.recordCount); }
    public synchronized long bytesUsed() { return bufferedBytes; }
    public synchronized int clear() {
        int discarded = depth();
        queue.clear(); pending = null; bufferedBytes = 0;
        return discarded;
    }
    private record Frozen(byte[] json, Class<? extends TelemetryRecordV1> type) { }
    private record Pending(String id, byte[] json, int recordCount, long recordBytes) { }
}
