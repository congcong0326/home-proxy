package org.congcong.controlmanager.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.congcong.common.telemetry.*;
import org.congcong.controlmanager.clickhouse.ClickHouseJdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class TelemetryStore implements ApplicationRunner {
    private final ClickHouseJdbcClient client;
    private final TelemetryProperties properties;
    private final CredentialGeoAnalysis geo;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
    private volatile boolean ready;
    private final java.util.concurrent.ConcurrentMap<String, Map<String, Object>> workerStatus = new java.util.concurrent.ConcurrentHashMap<>();

    public TelemetryStore(ClickHouseJdbcClient client, TelemetryProperties properties, CredentialGeoAnalysis geo) {
        this.client = client;
        this.properties = properties;
        this.geo = geo;
    }

    public void observeWorker(String workerId, Map<String, Object> reported) {
        Map<String, Object> status = new LinkedHashMap<>();
        // A bounded allowlist prevents arbitrary worker JSON becoming a status API payload.
        if (reported != null) {
            for (String key : List.of("enabled", "instanceId", "bufferMode", "restartRecoverySupported", "queueDepth", "bufferBytes", "bufferCapacity", "bufferMaxBytes", "dropped", "serializationFailures", "retries", "acks", "complete")) {
                Object value = reported.get(key);
                if (value instanceof Number || value instanceof Boolean || value instanceof String text && text.length() <= 128) status.put(key, value);
            }
        }
        status.put("capability", "V1_NEGOTIATED");
        status.put("lastSeenAt", Instant.now().toString());
        workerStatus.put(workerId, Map.copyOf(status));
    }

    public Map<String, Object> workerStatus(String workerId) {
        Map<String, Object> status = new LinkedHashMap<>(workerStatus.getOrDefault(workerId,
                Map.of("capability", "UNSUPPORTED_OR_DISABLED", "complete", false)));
        Object seen = status.get("lastSeenAt");
        status.put("online", seen != null && java.time.Duration.between(Instant.parse(seen.toString()), Instant.now()).getSeconds() < 15);
        return status;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isIngestEnabled() && !properties.isQueryEnabled()) return;
        try { initialize(); } catch (RuntimeException e) {
            // A new-table outage must not prevent the legacy application from starting.
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Telemetry initialization deferred: {}", e.toString());
        }
    }

    public void requireQueryAvailable() {
        if (!properties.isQueryEnabled()) throw new IllegalStateException("QUERY_DISABLED");
        initialize();
    }

    private synchronized void initialize() {
        if (ready) return;
        client.execute("CREATE DATABASE IF NOT EXISTS telemetry");
        Integer[] retention = {properties.getSessionRetentionDays(), properties.getDnsRetentionDays(),
                properties.getEventRetentionDays(), properties.getAuthRetentionDays()};
        for (TelemetrySchema schema : TelemetrySchema.values()) {
            Integer days = retention[schema.ordinal()];
            if (days != null && days <= 0) throw new IllegalArgumentException("Retention must be positive");
            client.execute(schema.ddl() + (days == null ? "" : " TTL occurred_at + INTERVAL " + days + " DAY"));
        }
        ready = true;
    }

    /** Synchronous durable CK write; replay keeps first received_at. No ACK on partial failure. */
    public synchronized TelemetryAckV1 ingest(String authenticatedWorkerId, TelemetryBatchV1 batch) throws Exception {
        if (!properties.isIngestEnabled()) throw new IllegalStateException("INGEST_DISABLED");
        initialize();
        if (batch.getProtocolVersion() != 1 || batch.getBatchId() == null
                || !batch.getBatchId().matches("[a-fA-F0-9-]{36}") || batch.recordCount() < 1
                || batch.recordCount() > properties.getMaxBatchRecords()
                || mapper.writeValueAsBytes(batch).length > properties.getMaxBatchBytes()) {
            throw new IllegalArgumentException("INVALID_BATCH");
        }
        Map<TelemetrySchema, List<? extends TelemetryRecordV1>> groups = new LinkedHashMap<>();
        groups.put(TelemetrySchema.SESSIONS, batch.getSessions());
        groups.put(TelemetrySchema.DNS, batch.getDnsQueries());
        groups.put(TelemetrySchema.EVENTS, batch.getEvents());
        groups.put(TelemetrySchema.AUTH, batch.getCredentialAuth());
        List<Pending> pending = new ArrayList<>();
        Map<String, String> inBatch = new HashMap<>();
        for (var group : groups.entrySet()) {
            for (TelemetryRecordV1 record : group.getValue()) {
                validate(record);
                // The management connection, not a self-declared JSON field, supplies node identity.
                record.setWorkerId(authenticatedWorkerId);
                String json = mapper.writeValueAsString(record);
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
                String key = group.getKey().name() + record.getRecordId();
                String previous = inBatch.putIfAbsent(key, hash);
                if (previous != null && !previous.equals(hash)) throw new IllegalArgumentException("RECORD_ID_CONFLICT");
                var stored = client.query("SELECT raw_hash FROM " + group.getKey().table
                        + " FINAL WHERE worker_id = ? AND record_id = ? LIMIT 1", authenticatedWorkerId, record.getRecordId());
                if (!stored.isEmpty()) {
                    if (!hash.equals(stored.get(0).get("raw_hash"))) throw new IllegalArgumentException("RECORD_ID_CONFLICT");
                } else if (previous == null) pending.add(new Pending(group.getKey(), record, json, hash));
            }
        }
        for (Pending row : pending) write(row);
        TelemetryAckV1 ack = new TelemetryAckV1();
        ack.setBatchId(batch.getBatchId());
        ack.setPersisted(true);
        return ack;
    }

    private void validate(TelemetryRecordV1 record) throws Exception {
        if (!Integer.valueOf(1).equals(record.getSchemaVersion()) || record.getOccurredAt() == null
                || record.getRecordId() == null || record.getWorkerInstanceId() == null) throw new IllegalArgumentException("INVALID_RECORD");
        UUID.fromString(record.getRecordId());
        UUID.fromString(record.getWorkerInstanceId());
        if (record.getConnectionId() != null) UUID.fromString(record.getConnectionId());
        if (record instanceof SessionLogV1 session) {
            if (session.getStartedAt() == null || session.getEndedAt() == null
                    || !session.getEndedAt().equals(session.getOccurredAt()) || session.getEndedAt().isBefore(session.getStartedAt())
                    || session.getConnectOutcome() == null || session.getSessionOutcome() == null
                    || session.getUploadBytes() == null || session.getDownloadBytes() == null
                    || session.getConnectOutcome() == ConnectOutcome.CONNECTED && session.getConnectedAt() == null) {
                throw new IllegalArgumentException("INCOMPLETE_SESSION_FACT");
            }
        }
        if (record instanceof CredentialAuthLogV1 auth && auth.getAttemptedUsername() != null
                && (auth.getAttemptedUsername().length() > 128 || auth.getAttemptedUsername().chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("UNSAFE_ATTEMPTED_USERNAME");
        }

        if (record instanceof SessionLogV1 session && !record.getRecordId().equals(session.getConnectionId())) throw new IllegalArgumentException("INVALID_SESSION_ID");
        if (record instanceof DnsQueryLogV1 dns && !record.getRecordId().equals(dns.getQueryId())) throw new IllegalArgumentException("INVALID_QUERY_ID");
        if (record instanceof ProxyEventLogV1 event && !record.getRecordId().equals(event.getEventId())) throw new IllegalArgumentException("INVALID_EVENT_ID");
        if (record instanceof CredentialAuthLogV1 auth && !record.getRecordId().equals(auth.getAuthAttemptId())) throw new IllegalArgumentException("INVALID_AUTH_ID");
        for (var field : TelemetrySchema.values()) {
            if (!field.model.isInstance(record)) continue;
            for (var f : field.fields()) {
                Object value = f.get(record);
                if (value instanceof Number number && number.longValue() < 0) throw new IllegalArgumentException("NEGATIVE_COUNTER");
                if (value instanceof String text && text.length() > 2048) throw new IllegalArgumentException("FIELD_TOO_LONG");
                if (f.getName().endsWith("Ip") && value != null && !com.google.common.net.InetAddresses.isInetAddress(value.toString())) throw new IllegalArgumentException("INVALID_IP");
            }
        }
    }

    private void write(Pending row) throws Exception {
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (var field : row.schema.fields()) {
            columns.add(TelemetrySchema.column(field));
            Object value = field.get(row.record);
            if (value instanceof Instant time) value = Timestamp.from(time);
            else if (value instanceof Enum<?> e) value = e.name();
            else if (field.getType() == List.class) value = value == null ? new String[0] : ((List<?>) value).toArray(String[]::new);
            values.add(value);
        }
        columns.addAll(List.of("received_at", "raw_hash", "raw_json", "analysis_json"));
        values.add(Timestamp.from(Instant.now()));
        values.add(row.hash);
        values.add(row.json);
        values.add(row.record instanceof CredentialAuthLogV1 auth ? mapper.writeValueAsString(geo.analyze(auth)) : null);
        client.execute("INSERT INTO " + row.schema.table + " (" + String.join(",", columns) + ") VALUES ("
                + String.join(",", Collections.nCopies(values.size(), "?")) + ")", values.toArray());
    }

    public List<Map<String, Object>> query(TelemetrySchema schema, String workerId, Instant from, Instant to, int limit, int offset, String connectionId) {
        if (!properties.isQueryEnabled()) throw new IllegalStateException("QUERY_DISABLED");
        if (workerId == null || workerId.isBlank() || !to.isAfter(from)
                || java.time.Duration.between(from, to).compareTo(java.time.Duration.ofDays(31)) > 0 || limit < 1 || limit > 200 || offset < 0 || offset > 10000) throw new IllegalArgumentException("INVALID_QUERY_SCOPE");
        initialize();
        List<Object> params = new ArrayList<>(List.of(workerId, Timestamp.from(from), Timestamp.from(to)));
        String filter = "";
        if (connectionId != null) { filter = " AND connection_id = ?"; params.add(connectionId); }
        params.add(limit); params.add(offset);
        return client.query("SELECT raw_json, received_at, analysis_json FROM " + schema.table
                + " FINAL WHERE worker_id = ? AND occurred_at >= ? AND occurred_at < ?" + filter
                + " ORDER BY occurred_at DESC, record_id LIMIT ? OFFSET ?", params.toArray()).stream().map(row -> {
                    try {
                        Map<String, Object> result = new LinkedHashMap<>(mapper.readValue(row.get("raw_json").toString(), Map.class));
                        Object received = row.get("received_at");
                        String receivedAt = received instanceof Timestamp time ? time.toInstant().toString()
                                : received instanceof java.time.LocalDateTime time ? time.toInstant(java.time.ZoneOffset.UTC).toString()
                                : received.toString().replace(" ", "T") + (received.toString().endsWith("Z") ? "" : "Z");
                        result.put("receivedAt", receivedAt);
                        if (row.get("analysis_json") != null) result.put("analysis", mapper.readValue(row.get("analysis_json").toString(), Map.class));
                        return result;
                    } catch (Exception e) { throw new IllegalStateException(e); }
                }).toList();
    }
    private record Pending(TelemetrySchema schema, TelemetryRecordV1 record, String json, String hash) { }
}
