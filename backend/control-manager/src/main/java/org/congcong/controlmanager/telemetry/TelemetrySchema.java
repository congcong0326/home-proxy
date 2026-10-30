package org.congcong.controlmanager.telemetry;

import org.congcong.common.telemetry.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** The V1 DTO is the allowlist of persisted fields; no credential/unknown JSON fields are stored. */
public enum TelemetrySchema {
    SESSIONS("sessions", "proxy_session_log_v1", SessionLogV1.class),
    DNS("dns", "dns_query_log_v1", DnsQueryLogV1.class),
    AUTH("credential-auth", "credential_auth_log_v1", CredentialAuthLogV1.class);

    public final String path;
    public final String table;
    public final Class<? extends TelemetryRecordV1> model;
    TelemetrySchema(String path, String table, Class<? extends TelemetryRecordV1> model) {
        this.path = path;
        this.table = table;
        this.model = model;
    }
    public Integer retentionDays(TelemetryProperties properties) {
        return switch (this) {
            case SESSIONS -> properties.getSessionRetentionDays();
            case DNS -> properties.getDnsRetentionDays();
            case AUTH -> properties.getAuthRetentionDays();
        };
    }
    public static TelemetrySchema fromPath(String path) {
        for (TelemetrySchema schema : values()) if (schema.path.equals(path)) return schema;
        throw new IllegalArgumentException("Unknown telemetry type");
    }
    public List<Field> fields() {
        List<Field> fields = new ArrayList<>();
        for (Class<?> type = model; type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    fields.add(field);
                }
            }
        }
        return fields;
    }
    public static String column(Field field) {
        return field.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }
    /** Integers, booleans and epoch-microsecond times are INTEGER; strings and JSON arrays are TEXT. */
    public String ddl() {
        List<String> columns = new ArrayList<>();
        for (Field field : fields()) {
            String name = column(field);
            Class<?> type = field.getType();
            String sqlType = type == Instant.class || type == Long.class || type == Integer.class || type == Boolean.class
                    ? "INTEGER" : "TEXT";
            boolean required = List.of("record_id", "worker_id", "worker_instance_id", "occurred_at", "schema_version").contains(name);
            columns.add(name + " " + sqlType + (required ? " NOT NULL" : ""));
        }
        columns.add("received_at INTEGER NOT NULL");
        columns.add("raw_hash TEXT NOT NULL");
        columns.add("raw_json TEXT NOT NULL");
        columns.add("analysis_json TEXT");
        return "CREATE TABLE IF NOT EXISTS " + table + " (" + String.join(",", columns) + ")";
    }

    /**
     * The unique key replaces ClickHouse {@code ReplacingMergeTree}/{@code FINAL}: a re-sent fact is ignored
     * at insert time, so every query reads exactly one row per fact. Every query filters by worker and time.
     */
    public List<String> indexes() {
        List<String> indexes = new ArrayList<>(List.of(
                "CREATE UNIQUE INDEX IF NOT EXISTS ux_" + table + "_fact ON " + table + " (worker_id, worker_instance_id, record_id)",
                "CREATE INDEX IF NOT EXISTS ix_" + table + "_worker_time ON " + table + " (worker_id, occurred_at)",
                "CREATE INDEX IF NOT EXISTS ix_" + table + "_time ON " + table + " (occurred_at)"));
        switch (this) {
            case SESSIONS -> indexes.add("CREATE INDEX IF NOT EXISTS ix_" + table + "_inbound ON " + table
                    + " (worker_id, inbound_id, occurred_at)");
            case AUTH -> indexes.add("CREATE INDEX IF NOT EXISTS ix_" + table + "_connection ON " + table
                    + " (worker_id, worker_instance_id, connection_id)");
            case DNS -> { }
        }
        return indexes;
    }
}
