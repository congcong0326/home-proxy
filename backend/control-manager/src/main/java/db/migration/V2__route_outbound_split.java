package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Splits outbound settings out of {@code routes}, see docs/development/route-outbound-split-phase1-design.md.
 *
 * <p>Every legacy route is converted one-to-one so the compiled worker config stays equivalent:
 * DIRECT/BLOCK point at the built-in outbounds, OUTBOUND_PROXY points at a de-duplicated outbound,
 * DESTINATION_OVERRIDE and DNS_REWRITING become fixed-target rewrites. Legacy {@code outbound_proxy_*}
 * columns are left untouched (frozen) for rollback and dropped by a later migration.
 */
public class V2__route_outbound_split extends BaseJavaMigration {
    private static final String NOW = "strftime('%Y-%m-%d %H:%M:%f000', 'now')";

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE outbounds (
                        id           INTEGER PRIMARY KEY AUTOINCREMENT,
                        name         TEXT    NOT NULL COLLATE NOCASE,
                        kind         TEXT    NOT NULL,
                        builtin_key  TEXT,
                        proxy_type   TEXT,
                        host         TEXT,
                        port         INTEGER,
                        username     TEXT,
                        password     TEXT,
                        enc_algo     TEXT,
                        config_json  TEXT,
                        notes        TEXT,
                        created_at   TEXT    NOT NULL,
                        updated_at   TEXT    NOT NULL,
                        CONSTRAINT uk_outbounds_name UNIQUE (name),
                        CONSTRAINT uk_outbounds_builtin UNIQUE (builtin_key)
                    )""");
            statement.execute("""
                    CREATE TABLE rewrite_tables (
                        id         INTEGER PRIMARY KEY AUTOINCREMENT,
                        name       TEXT    NOT NULL COLLATE NOCASE,
                        notes      TEXT,
                        created_at TEXT    NOT NULL,
                        updated_at TEXT    NOT NULL,
                        CONSTRAINT uk_rewrite_tables_name UNIQUE (name)
                    )""");
            statement.execute("""
                    CREATE TABLE rewrite_entries (
                        id             INTEGER PRIMARY KEY AUTOINCREMENT,
                        table_id       INTEGER NOT NULL,
                        sort_order     INTEGER NOT NULL,
                        domain_pattern TEXT    NOT NULL COLLATE NOCASE,
                        target_host    TEXT    NOT NULL,
                        target_port    INTEGER NOT NULL,
                        enabled        INTEGER NOT NULL DEFAULT 1,
                        notes          TEXT,
                        created_at     TEXT    NOT NULL,
                        updated_at     TEXT    NOT NULL,
                        CONSTRAINT uk_rewrite_entries_pattern UNIQUE (table_id, domain_pattern),
                        CONSTRAINT fk_rewrite_entries_table FOREIGN KEY (table_id) REFERENCES rewrite_tables (id) ON DELETE CASCADE
                    )""");
            statement.execute("CREATE INDEX idx_rewrite_entries_table_order ON rewrite_entries (table_id, sort_order)");
            statement.execute("ALTER TABLE routes ADD COLUMN action TEXT");
            statement.execute("ALTER TABLE routes ADD COLUMN outbound_id INTEGER REFERENCES outbounds (id)");
            statement.execute("ALTER TABLE routes ADD COLUMN rewrite_table_id INTEGER REFERENCES rewrite_tables (id)");
            statement.execute("ALTER TABLE routes ADD COLUMN rewrite_target_host TEXT");
            statement.execute("ALTER TABLE routes ADD COLUMN rewrite_target_port INTEGER");
            statement.execute("CREATE INDEX idx_routes_outbound ON routes (outbound_id)");
            statement.execute("CREATE INDEX idx_routes_rewrite_table ON routes (rewrite_table_id)");
        }

        long directId = insertBuiltin(connection, "直连", "DIRECT");
        long blockId = insertBuiltin(connection, "阻断", "BLOCK");
        Map<OutboundKey, Long> outbounds = new LinkedHashMap<>();
        Set<String> usedNames = new HashSet<>(List.of("直连", "阻断"));

        for (LegacyRoute route : loadRoutes(connection)) {
            switch (route.policy) {
                case "DIRECT" -> update(connection, route.id, "FORWARD", directId, null, null);
                case "BLOCK" -> update(connection, route.id, "FORWARD", blockId, null, null);
                case "OUTBOUND_PROXY" -> {
                    OutboundKey key = route.outboundKey();
                    Long outboundId = outbounds.get(key);
                    if (outboundId == null) {
                        outboundId = insertOutbound(connection, key, uniqueName(usedNames, key.defaultName()));
                        outbounds.put(key, outboundId);
                    }
                    update(connection, route.id, "FORWARD", outboundId, null, null);
                }
                case "DESTINATION_OVERRIDE" -> update(connection, route.id, "REWRITE_DESTINATION", directId,
                        route.host, route.port);
                case "DNS_REWRITING" -> update(connection, route.id, "REWRITE_DNS", null, route.host, null);
                default -> throw new IllegalStateException("未知的路由策略: route=" + route.id + ", policy=" + route.policy);
            }
        }
    }

    private List<LegacyRoute> loadRoutes(Connection connection) throws SQLException {
        List<LegacyRoute> routes = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                     SELECT id, policy, outbound_proxy_type, outbound_proxy_host, outbound_proxy_port,
                            outbound_proxy_username, outbound_proxy_password, outbound_proxy_enc_algo,
                            outbound_proxy_config_json
                     FROM routes ORDER BY id""")) {
            while (rs.next()) {
                Object port = rs.getObject("outbound_proxy_port");
                routes.add(new LegacyRoute(rs.getLong("id"), rs.getString("policy"),
                        rs.getString("outbound_proxy_type"), rs.getString("outbound_proxy_host"),
                        port == null ? null : ((Number) port).intValue(),
                        rs.getString("outbound_proxy_username"), rs.getString("outbound_proxy_password"),
                        rs.getString("outbound_proxy_enc_algo"), rs.getString("outbound_proxy_config_json")));
            }
        }
        return routes;
    }

    private long insertBuiltin(Connection connection, String name, String key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO outbounds (name, kind, builtin_key, created_at, updated_at) VALUES (?, ?, ?, "
                        + NOW + ", " + NOW + ")", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, key);
            ps.setString(3, key);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private long insertOutbound(Connection connection, OutboundKey key, String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO outbounds (name, kind, proxy_type, host, port, username, password, enc_algo, config_json,
                                       created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, %s, %s)""".formatted(NOW, NOW), Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, key.isDnsUpstream() ? "DNS_UPSTREAM" : "PROXY");
            ps.setString(3, key.type);
            ps.setString(4, key.host);
            setInteger(ps, 5, key.port);
            ps.setString(6, key.username);
            ps.setString(7, key.password);
            ps.setString(8, key.encAlgo);
            ps.setString(9, key.configJson);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private void update(Connection connection, long routeId, String action, Long outboundId,
                        String rewriteHost, Integer rewritePort) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE routes SET action = ?, outbound_id = ?, rewrite_target_host = ?, rewrite_target_port = ?
                WHERE id = ?""")) {
            ps.setString(1, action);
            if (outboundId == null) ps.setNull(2, Types.INTEGER); else ps.setLong(2, outboundId);
            ps.setString(3, rewriteHost);
            setInteger(ps, 4, rewritePort);
            ps.setLong(5, routeId);
            ps.executeUpdate();
        }
    }

    private static void setInteger(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) ps.setNull(index, Types.INTEGER); else ps.setInt(index, value);
    }

    private static long generatedId(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (!keys.next()) throw new IllegalStateException("无法获取新出站 ID");
            return keys.getLong(1);
        }
    }

    private static String uniqueName(Set<String> used, String base) {
        String name = base;
        for (int i = 2; !used.add(name.toLowerCase()); i++) {
            name = base + " (" + i + ")";
        }
        return name;
    }

    private record LegacyRoute(long id, String policy, String type, String host, Integer port, String username,
                               String password, String encAlgo, String configJson) {
        OutboundKey outboundKey() {
            return new OutboundKey(type, host, port, username, password, encAlgo, configJson);
        }
    }

    private record OutboundKey(String type, String host, Integer port, String username, String password,
                               String encAlgo, String configJson) {
        boolean isDnsUpstream() {
            return "DNS_SERVER".equals(type) || "DOT".equals(type);
        }

        String defaultName() {
            String prefix = isDnsUpstream() ? ("DOT".equals(type) ? "DoT" : "DNS") : Objects.toString(type, "PROXY");
            return prefix + " " + host + ":" + port;
        }
    }
}
