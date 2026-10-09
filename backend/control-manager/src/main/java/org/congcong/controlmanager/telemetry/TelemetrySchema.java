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
    EVENTS("events", "proxy_event_log_v1", ProxyEventLogV1.class),
    AUTH("credential-auth", "credential_auth_log_v1", CredentialAuthLogV1.class);

    public final String path;
    public final String table;
    public final Class<? extends TelemetryRecordV1> model;
    TelemetrySchema(String path, String table, Class<? extends TelemetryRecordV1> model) {
        this.path = path;
        this.table = "telemetry." + table;
        this.model = model;
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
    public String ddl() {
        List<String> columns = new ArrayList<>();
        for (Field field : fields()) {
            String name = column(field);
            Class<?> type = field.getType();
            String sqlType = type == Instant.class ? "DateTime64(6, 'UTC')"
                    : type == Long.class ? "UInt64" : type == Integer.class ? "UInt32"
                    : type == Boolean.class ? "Bool" : type == List.class ? "Array(String)" : "String";
            boolean required = List.of("record_id", "worker_id", "worker_instance_id", "occurred_at", "schema_version").contains(name);
            columns.add(name + " " + (required || type == List.class ? sqlType : "Nullable(" + sqlType + ")"));
        }
        columns.add("received_at DateTime64(6, 'UTC')");
        columns.add("raw_hash String");
        columns.add("raw_json String");
        columns.add("analysis_json Nullable(String)");
        return "CREATE TABLE IF NOT EXISTS " + table + " (" + String.join(",", columns) + ") "
                + "ENGINE = ReplacingMergeTree PARTITION BY toYYYYMM(occurred_at) "
                + "ORDER BY (worker_id, worker_instance_id, occurred_at, record_id)";
    }
}
