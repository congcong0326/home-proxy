package org.congcong.proxyworker.telemetry;

import lombok.extern.slf4j.Slf4j;
import org.congcong.common.telemetry.*;
import org.congcong.proxyworker.config.ProxyWorkerConfig;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public final class TelemetryPublisher {
    public static final String INSTANCE_ID = UUID.randomUUID().toString();
    private static final TelemetryPublisher INSTANCE = new TelemetryPublisher();
    private final boolean enabled = Boolean.parseBoolean(System.getProperty("observability.enabled",
            System.getenv().getOrDefault("OBSERVABILITY_ENABLED", "true")));
    private volatile boolean logReportingEnabled = true;
    private final boolean authEnabled = Boolean.parseBoolean(System.getenv().getOrDefault("OBSERVABILITY_AUTH_ENABLED", "true"));
    private final boolean eventsEnabled = Boolean.parseBoolean(System.getenv().getOrDefault("OBSERVABILITY_EVENTS_ENABLED", "true"));
    private final int capacity = Integer.parseInt(System.getenv().getOrDefault("OBSERVABILITY_BUFFER_CAPACITY", "5000"));
    private final long maxBytes = Long.parseLong(System.getenv().getOrDefault("OBSERVABILITY_BUFFER_MAX_BYTES", "4194304"));
    private final TelemetryBuffer buffer = new TelemetryBuffer(capacity, maxBytes);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong serializationFailures = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong acks = new AtomicLong();

    private TelemetryPublisher() { }
    public static TelemetryPublisher getInstance() { return INSTANCE; }
    /** Existing per-worker setting now controls V1 collection and transport. */
    public void setLogReportingEnabled(Boolean enabled) { logReportingEnabled = enabled == null || enabled; }
    public boolean enabled() { return enabled && logReportingEnabled; }
    public boolean authEnabled() { return enabled() && authEnabled; }
    public boolean eventsEnabled() { return enabled() && eventsEnabled; }

    public void publish(TelemetryRecordV1 record) {
        if (!enabled()) return;
        try {
            record.setWorkerId(ProxyWorkerConfig.getInstance().getWorkerId());
            record.setWorkerInstanceId(INSTANCE_ID);
            if (!buffer.offer(record)) dropped.incrementAndGet();
        } catch (Exception error) {
            dropped.incrementAndGet(); serializationFailures.incrementAndGet();
        }
    }

    /** Independent sender reads a stable batch; no file I/O or recovery is involved. */
    public TelemetryBatchV1 peek() {
        if (!enabled()) return null;
        try { return buffer.peek(); }
        catch (Exception error) {
            serializationFailures.incrementAndGet();
            log.warn("Telemetry batch serialization failed: {}", error.toString());
            return null;
        }
    }
    public void acknowledge(TelemetryAckV1 ack) {
        if (buffer.acknowledge(ack)) acks.incrementAndGet();
    }
    public void retried() { retries.incrementAndGet(); }
    public Map<String, Object> status() {
        return Map.ofEntries(Map.entry("enabled", enabled()), Map.entry("instanceId", INSTANCE_ID),
                Map.entry("bufferMode", "MEMORY"), Map.entry("restartRecoverySupported", false),
                Map.entry("queueDepth", buffer.depth()), Map.entry("bufferBytes", buffer.bytesUsed()),
                Map.entry("bufferCapacity", capacity), Map.entry("bufferMaxBytes", maxBytes),
                Map.entry("dropped", dropped.get()), Map.entry("serializationFailures", serializationFailures.get()),
                Map.entry("retries", retries.get()), Map.entry("acks", acks.get()),
                Map.entry("complete", dropped.get() == 0 && serializationFailures.get() == 0));
    }
    public void shutdown() {
        int discarded = buffer.clear();
        if (discarded > 0) log.info("Worker stopped with {} unconfirmed telemetry facts in its memory buffer", discarded);
    }
}
