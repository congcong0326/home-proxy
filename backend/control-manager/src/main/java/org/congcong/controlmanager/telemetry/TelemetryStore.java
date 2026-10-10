package org.congcong.controlmanager.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.congcong.common.telemetry.*;
import org.congcong.controlmanager.clickhouse.ClickHouseJdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
@Slf4j
public class TelemetryStore implements ApplicationRunner {
    private final ClickHouseJdbcClient client;
    private final TelemetryProperties properties;
    private final CredentialGeoAnalysis geo;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
    private volatile boolean ready;
    private final java.util.concurrent.ConcurrentMap<String, Map<String, Object>> workerStatus = new java.util.concurrent.ConcurrentHashMap<>();

    private final Object bufferLock = new Object();
    private final ReentrantLock flushLock = new ReentrantLock();
    private final Map<TelemetrySchema, Deque<Pending>> buffers = new EnumMap<>(TelemetrySchema.class);
    private final LongSupplier nanoTime;
    private int bufferedRecords;
    private long bufferedBytes;
    private boolean stopping;

    @Autowired
    public TelemetryStore(ClickHouseJdbcClient client, TelemetryProperties properties, CredentialGeoAnalysis geo) {
        this(client, properties, geo, System::nanoTime);
    }

    TelemetryStore(ClickHouseJdbcClient client, TelemetryProperties properties, CredentialGeoAnalysis geo,
                   LongSupplier nanoTime) {
        this.client = client;
        this.properties = properties;
        this.geo = geo;
        this.nanoTime = nanoTime;
        for (TelemetrySchema schema : TelemetrySchema.values()) buffers.put(schema, new ArrayDeque<>());
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

    /** ACK means the complete batch is frozen in manager memory, not yet stored in CK. */
    public TelemetryAckV1 ingest(String authenticatedWorkerId, TelemetryBatchV1 batch) throws Exception {
        if (!properties.isIngestEnabled()) throw new IllegalStateException("INGEST_DISABLED");
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
        Instant receivedAt = Instant.now();
        long queuedAt = nanoTime.getAsLong();
        long bytes = 0;
        for (var group : groups.entrySet()) {
            for (TelemetryRecordV1 record : group.getValue()) {
                validate(record);
                // Bind identity to the authenticated connection and freeze the accepted fact.
                record.setWorkerId(authenticatedWorkerId);
                String json = mapper.writeValueAsString(record);
                byte[] encoded = json.getBytes(StandardCharsets.UTF_8);
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
                long sizeBytes = encoded.length + 512L;
                pending.add(new Pending(group.getKey(), mapper.readValue(json, group.getKey().model),
                        json, hash, receivedAt, queuedAt, sizeBytes));
                bytes += sizeBytes;
            }
        }
        synchronized (bufferLock) {
            if (stopping) throw new IllegalStateException("MANAGER_STOPPING");
            if (pending.size() > properties.getBufferCapacity() - bufferedRecords
                    || bytes > properties.getBufferMaxBytes() - bufferedBytes) {
                throw new IllegalStateException("MANAGER_BUFFER_FULL");
            }
            // Admission is all-or-nothing: a rejected batch must not leave a partial copy.
            for (Pending row : pending) buffers.get(row.schema).addLast(row);
            bufferedRecords += pending.size();
            bufferedBytes += bytes;
        }
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

    @Scheduled(fixedDelayString = "${observability.flush-check-ms:1000}")
    public void flushDue() {
        if (!flushLock.tryLock()) return;
        try { flush(false); }
        finally { flushLock.unlock(); }
    }

    @PreDestroy
    void stop() {
        synchronized (bufferLock) { stopping = true; }
        flushLock.lock();
        try { flush(true); }
        finally { flushLock.unlock(); }
    }

    private void flush(boolean force) {
        for (TelemetrySchema schema : TelemetrySchema.values()) {
            boolean more;
            do {
                List<Pending> rows;
                synchronized (bufferLock) {
                    Deque<Pending> queue = buffers.get(schema);
                    if (queue.isEmpty()) break;
                    int batchSize = Math.max(1, properties.getFlushBatchSize());
                    long ageMs = (nanoTime.getAsLong() - queue.peekFirst().queuedAt) / 1_000_000;
                    if (!force && queue.size() < batchSize
                            && ageMs < Math.max(1, properties.getFlushMaxDelayMs())) break;
                    rows = queue.stream().limit(batchSize).toList();
                }
                try {
                    initialize();
                    writeBatch(schema, rows);
                } catch (Exception error) {
                    // Keep the prefix in memory, including its original age, for the next attempt.
                    log.warn("Telemetry batch flush failed, table={}, records={}; retained for retry", schema.table, rows.size(), error);
                    break;
                }
                synchronized (bufferLock) {
                    for (Pending row : rows) {
                        buffers.get(schema).removeFirst();
                        bufferedRecords--;
                        bufferedBytes -= row.sizeBytes;
                    }
                    more = !buffers.get(schema).isEmpty();
                }
            } while (more);
        }
    }

    public Map<String, Object> bufferStatus() {
        synchronized (bufferLock) {
            Map<String, Integer> depths = new LinkedHashMap<>();
            buffers.forEach((schema, queue) -> depths.put(schema.path, queue.size()));
            return Map.of("queueDepth", bufferedRecords, "bufferBytes", bufferedBytes,
                    "bufferCapacity", properties.getBufferCapacity(), "bufferMaxBytes", properties.getBufferMaxBytes(),
                    "flushBatchSize", properties.getFlushBatchSize(), "flushMaxDelayMs", properties.getFlushMaxDelayMs(),
                    "tables", depths);
        }
    }

    private void writeBatch(TelemetrySchema schema, List<Pending> rows) throws Exception {
        var fields = schema.fields();
        List<String> columns = new ArrayList<>(fields.stream().map(TelemetrySchema::column).toList());
        columns.addAll(List.of("received_at", "raw_hash", "raw_json", "analysis_json"));
        List<List<Object>> params = new ArrayList<>(rows.size());
        for (Pending row : rows) {
            List<Object> values = new ArrayList<>();
            for (var field : fields) {
                Object value = field.get(row.record);
                if (value instanceof Instant time) value = Timestamp.from(time);
                else if (value instanceof Enum<?> e) value = e.name();
                else if (field.getType() == List.class) value = value == null ? new String[0] : ((List<?>) value).toArray(String[]::new);
                values.add(value);
            }
            values.add(Timestamp.from(row.receivedAt));
            values.add(row.hash);
            values.add(row.json);
            values.add(row.record instanceof CredentialAuthLogV1 auth ? mapper.writeValueAsString(geo.analyze(auth)) : null);
            params.add(values);
        }
        client.batchExecute("INSERT INTO " + schema.table + " (" + String.join(",", columns) + ") VALUES ("
                + String.join(",", Collections.nCopies(columns.size(), "?")) + ")", params);
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
                        result.remove("rateLimitPolicyId");
                        result.remove("rateLimitWaitUs");
                        Object received = row.get("received_at");
                        String receivedAt = received instanceof Timestamp time ? time.toInstant().toString()
                                : received instanceof java.time.LocalDateTime time ? time.toInstant(java.time.ZoneOffset.UTC).toString()
                                : received.toString().replace(" ", "T") + (received.toString().endsWith("Z") ? "" : "Z");
                        result.put("receivedAt", receivedAt);
                        attachAnalysis(result, row.get("analysis_json"), row.get("raw_json").toString());
                        return result;
                    } catch (Exception e) { throw new IllegalStateException(e); }
                }).toList();
    }

    public record AuditPage(List<Map<String, Object>> items, String total, int limit, int offset,
                            int maxOffset, String timeBoundary) { }

    public AuditPage page(TelemetrySchema schema, TelemetryQueryRequest request) {
        SqlScope scope = auditScope(schema, request);
        initialize();
        var count = client.query("SELECT count() AS total FROM " + schema.table + " FINAL WHERE " + scope.where,
                scope.params.toArray());
        List<Object> params = new ArrayList<>(scope.params);
        params.add(request.getLimit());
        params.add(request.getOffset());
        var rows = readAuditRows("SELECT raw_json, received_at, analysis_json FROM " + schema.table
                + " FINAL WHERE " + scope.where + " ORDER BY occurred_at DESC, record_id LIMIT ? OFFSET ?", params);
        if (schema == TelemetrySchema.SESSIONS) attachCredentialAttempts(rows, request);
        return new AuditPage(rows, count.get(0).get("total").toString(), request.getLimit(), request.getOffset(),
                10000, "[from,to)");
    }

    public Map<String, Object> detail(TelemetrySchema schema, String recordId, TelemetryQueryRequest request) {
        UUID.fromString(recordId);
        SqlScope scope = auditScope(schema, request);
        initialize();
        List<Object> params = new ArrayList<>(scope.params);
        params.add(recordId);
        var rows = readAuditRows("SELECT raw_json, received_at, analysis_json FROM " + schema.table
                + " FINAL WHERE " + scope.where + " AND record_id = ? LIMIT 1", params);
        if (schema == TelemetrySchema.SESSIONS) attachCredentialAttempts(rows, request);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private SqlScope auditScope(TelemetrySchema schema, TelemetryQueryRequest request) {
        if (!properties.isQueryEnabled()) throw new IllegalStateException("QUERY_DISABLED");
        if (request.getWorkerId() == null || request.getWorkerId().isBlank() || request.getWorkerId().length() > 128
                || request.getFrom() == null || request.getTo() == null || !request.getTo().isAfter(request.getFrom())
                || java.time.Duration.between(request.getFrom(), request.getTo()).compareTo(java.time.Duration.ofDays(31)) > 0
                || request.getLimit() < 1 || request.getLimit() > 200 || request.getOffset() < 0 || request.getOffset() > 10000) {
            throw new IllegalArgumentException("Worker/time required; window <=31 days, limit 1..200, offset 0..10000");
        }
        StringBuilder where = new StringBuilder("worker_id = ? AND occurred_at >= ? AND occurred_at < ?");
        List<Object> params = new ArrayList<>(List.of(request.getWorkerId(), Timestamp.from(request.getFrom()), Timestamp.from(request.getTo())));
        addFilter(where, params, request.getConnectionId(), schema == TelemetrySchema.DNS
                ? "(connection_id = ? OR parent_connection_id = ?)" : "connection_id = ?", schema == TelemetrySchema.DNS ? 2 : 1);
        if (request.getConnectionId() != null && !request.getConnectionId().isBlank()) UUID.fromString(request.getConnectionId());
        if (schema == TelemetrySchema.SESSIONS && has(request.getUsername())) {
            if (request.getUsername().length() > 2048) throw new IllegalArgumentException("Filter too long");
            where.append(" AND (positionCaseInsensitiveUTF8(ifNull(username,''), ?) > 0 OR (worker_instance_id, connection_id) IN ("
                    + "SELECT worker_instance_id, connection_id FROM " + TelemetrySchema.AUTH.table
                    + " FINAL WHERE worker_id = ? AND occurred_at >= ? AND occurred_at < ?"
                    + " AND positionCaseInsensitiveUTF8(ifNull(attempted_username,''), ?) > 0))");
            params.addAll(List.of(request.getUsername(), request.getWorkerId(),
                    Timestamp.from(request.getTo().minus(java.time.Duration.ofDays(31))), Timestamp.from(request.getTo()), request.getUsername()));
        } else addFilter(where, params, request.getUsername(), schema == TelemetrySchema.AUTH
                ? "(positionCaseInsensitiveUTF8(ifNull(username,''), ?) > 0 OR positionCaseInsensitiveUTF8(ifNull(attempted_username,''), ?) > 0 OR positionCaseInsensitiveUTF8(ifNull(verified_username,''), ?) > 0)"
                : "positionCaseInsensitiveUTF8(ifNull(username,''), ?) > 0", schema == TelemetrySchema.AUTH ? 3 : 1);
        addFilter(where, params, request.getClientIp(), "client_ip = ?", 1);
        addFilter(where, params, request.getProtocol(), "inbound_protocol = ?", 1);
        String outcome = schema == TelemetrySchema.SESSIONS ? "session_outcome" : schema == TelemetrySchema.AUTH ? "auth_outcome" : "outcome";
        addFilter(where, params, request.getOutcome(), outcome + " = ?", 1);
        if (schema == TelemetrySchema.SESSIONS) {
            addFilter(where, params, request.getTarget(), "(positionCaseInsensitiveUTF8(ifNull(logical_target_host,''), ?) > 0 OR positionCaseInsensitiveUTF8(ifNull(effective_target_host,''), ?) > 0 OR positionCaseInsensitiveUTF8(ifNull(original_dst_ip,''), ?) > 0 OR positionCaseInsensitiveUTF8(ifNull(selected_target_ip,''), ?) > 0)", 4);
            addFilter(where, params, request.getRoute(), "(positionCaseInsensitiveUTF8(ifNull(route_name,''), ?) > 0 OR toString(route_id) = ?)", 2);
            addFilter(where, params, request.getTrafficCategory(), "traffic_category = ?", 1);
            addFilter(where, params, request.getConnectOutcome(), "connect_outcome = ?", 1);
        } else if (schema == TelemetrySchema.DNS) {
            addFilter(where, params, request.getTarget(), "positionCaseInsensitiveUTF8(ifNull(qname,''), ?) > 0", 1);
            addFilter(where, params, request.getQtype(), "qtype = ?", 1);
        } else if (schema == TelemetrySchema.EVENTS) {
            addFilter(where, params, request.getEventType(), "event_type = ?", 1);
        } else {
            addFilter(where, params, request.getAuthMethod(), "auth_method = ?", 1);
            addFilter(where, params, request.getExactAuthUsername(), "(attempted_username = ? OR verified_username = ? OR username = ?)", 3);
            if (has(request.getAuthOutcomeGroup())) {
                if ("SUCCESS".equals(request.getAuthOutcomeGroup())) where.append(" AND auth_outcome = 'SUCCESS'");
                else if ("FAILURE".equals(request.getAuthOutcomeGroup())) where.append(" AND auth_outcome IN ('FAILURE','DENIED','MALFORMED','ERROR')");
                else throw new IllegalArgumentException("Invalid authentication result group");
            }
        }
        if (schema != TelemetrySchema.SESSIONS && (has(request.getRoute()) || has(request.getTrafficCategory()) || has(request.getConnectOutcome()))
                || schema != TelemetrySchema.DNS && has(request.getQtype())
                || schema != TelemetrySchema.EVENTS && has(request.getEventType())
                || schema != TelemetrySchema.AUTH && (has(request.getAuthMethod()) || has(request.getAuthOutcomeGroup()) || has(request.getExactAuthUsername()))
                || schema != TelemetrySchema.DNS && schema != TelemetrySchema.SESSIONS && has(request.getTarget())) {
            throw new IllegalArgumentException("Filter unsupported for this record type");
        }
        return new SqlScope(where.toString(), params);
    }

    private boolean has(String value) { return value != null && !value.isBlank(); }

    /** Read-time association; neither the original session fact nor verified identity is rewritten. */
    private void attachCredentialAttempts(List<Map<String, Object>> sessions, TelemetryQueryRequest request) {
        Map<List<String>, List<Map<String, Object>>> associated = new LinkedHashMap<>();
        for (var session : sessions) {
            if (session.get("connectionId") instanceof String connection && session.get("workerInstanceId") instanceof String instance) {
                associated.putIfAbsent(List.of(instance, connection), new ArrayList<>());
            }
        }
        if (associated.isEmpty()) return;
        List<Object> params = new ArrayList<>(List.of(request.getWorkerId(),
                Timestamp.from(request.getTo().minus(java.time.Duration.ofDays(31))), Timestamp.from(request.getTo())));
        associated.keySet().forEach(params::addAll);
        var attempts = client.query("SELECT worker_instance_id, connection_id, auth_attempt_id, attempted_username, auth_outcome, auth_method, occurred_at FROM "
                + TelemetrySchema.AUTH.table + " FINAL WHERE worker_id = ? AND occurred_at >= ? AND occurred_at < ?"
                + " AND (worker_instance_id, connection_id) IN (" + String.join(",", Collections.nCopies(associated.size(), "(?,?)")) + ")"
                + " ORDER BY occurred_at DESC, record_id LIMIT 21 BY worker_instance_id, connection_id", params.toArray());
        for (var attempt : attempts) {
            var target = associated.get(List.of(attempt.get("worker_instance_id").toString(), attempt.get("connection_id").toString()));
            if (target == null) continue;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("authAttemptId", attempt.get("auth_attempt_id"));
            result.put("attemptedUsername", attempt.get("attempted_username"));
            result.put("authOutcome", attempt.get("auth_outcome"));
            result.put("authMethod", attempt.get("auth_method"));
            Object occurred = attempt.get("occurred_at");
            result.put("occurredAt", occurred instanceof Timestamp time ? time.toInstant().toString()
                    : occurred instanceof java.time.LocalDateTime time ? time.toInstant(java.time.ZoneOffset.UTC).toString()
                    : occurred.toString().replace(" ", "T") + (occurred.toString().endsWith("Z") ? "" : "Z"));
            target.add(result);
        }
        for (var session : sessions) {
            if (!(session.get("connectionId") instanceof String connection) || !(session.get("workerInstanceId") instanceof String instance)) continue;
            var matches = associated.get(List.of(instance, connection));
            session.put("credentialAuthAttempts", matches.stream().limit(20).toList());
            session.put("credentialAuthAttemptsTruncated", matches.size() > 20);
        }
    }

    private void addFilter(StringBuilder where, List<Object> params, String value, String predicate, int copies) {
        if (!has(value)) return;
        if (value.length() > 2048) throw new IllegalArgumentException("Filter too long");
        where.append(" AND ").append(predicate);
        for (int i = 0; i < copies; i++) params.add(value);
    }

    private List<Map<String, Object>> readAuditRows(String sql, List<Object> params) {
        return client.query(sql, params.toArray()).stream().map(row -> {
            try {
                Map<String, Object> result = new LinkedHashMap<>(mapper.readValue(row.get("raw_json").toString(), Map.class));
                result.remove("rateLimitPolicyId");
                result.remove("rateLimitWaitUs");
                Object received = row.get("received_at");
                result.put("receivedAt", received instanceof Timestamp time ? time.toInstant().toString()
                        : received instanceof java.time.LocalDateTime time ? time.toInstant(java.time.ZoneOffset.UTC).toString()
                        : received.toString().replace(" ", "T") + (received.toString().endsWith("Z") ? "" : "Z"));
                attachAnalysis(result, row.get("analysis_json"), row.get("raw_json").toString());
                return result;
            } catch (Exception e) { throw new IllegalStateException("INVALID_STORED_FACT", e); }
        }).toList();
    }

    private void attachAnalysis(Map<String, Object> result, Object storedAnalysis, String rawJson) throws Exception {
        Map<String, Object> analysis = storedAnalysis == null ? null
                : mapper.readValue(storedAnalysis.toString(), Map.class);
        // Repair unavailable historical enrichment at query time, without rewriting raw facts.
        if (result.containsKey("authAttemptId")
                && (analysis == null || "DB_UNAVAILABLE".equals(analysis.get("geoLookupStatus")))) {
            var refreshed = geo.analyze(mapper.readValue(rawJson, CredentialAuthLogV1.class));
            if (refreshed != null) analysis = mapper.convertValue(refreshed, Map.class);
        }
        if (analysis != null) result.put("analysis", analysis);
    }

    private record SqlScope(String where, List<Object> params) { }
    private record Pending(TelemetrySchema schema, TelemetryRecordV1 record, String json, String hash,
                           Instant receivedAt, long queuedAt, long sizeBytes) { }
}
