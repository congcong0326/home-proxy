package org.congcong.controlmanager.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2 converts every legacy route one-to-one; see docs/development/route-outbound-split-phase1-design.md section 6.
 * Equivalence of the compiled worker config is checked against production copies with
 * {@link AggregateConfigSnapshotExportTest}.
 */
class RouteOutboundSplitMigrationTest {
    private static final String NOW = "'2026-10-30 00:00:00.000000'";

    @TempDir
    Path dir;

    @Test
    void convertsLegacyRoutesAndDeduplicatesUpstreams() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                "jdbc:sqlite:" + dir.resolve("home-proxy.db"), true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        flyway(dataSource, "1").migrate();
        insertRoute(jdbc, 1, "DIRECT", null, null, null, null, null, null);
        insertRoute(jdbc, 2, "BLOCK", null, null, null, null, null, null);
        insertRoute(jdbc, 3, "OUTBOUND_PROXY", "VLESS_REALITY", "45.62.112.20", 443, null, "uuid-secret", "{\"flow\":\"x\"}");
        insertRoute(jdbc, 4, "OUTBOUND_PROXY", "VLESS_REALITY", "45.62.112.20", 443, null, "uuid-secret", "{\"flow\":\"x\"}");
        insertRoute(jdbc, 5, "OUTBOUND_PROXY", "DNS_SERVER", "8.8.8.8,1.1.1.1", 53, null, null, "{}");
        insertRoute(jdbc, 6, "OUTBOUND_PROXY", "SHADOW_SOCKS", "ss.example.com", 18082, "user", "pw", "{}");
        insertRoute(jdbc, 7, "DESTINATION_OVERRIDE", null, "192.168.10.85", 8096, null, null, null);
        insertRoute(jdbc, 8, "DNS_REWRITING", null, "192.168.10.1", null, null, null, null);
        // A stale proxy host left on a DIRECT route must not create an outbound.
        insertRoute(jdbc, 9, "DIRECT", null, "stale.example.com", 1080, null, null, null);

        flyway(dataSource, "latest").migrate();

        List<Map<String, Object>> outbounds = jdbc.queryForList(
                "SELECT id, name, kind, builtin_key, proxy_type, host, port, username, password, config_json FROM outbounds ORDER BY id");
        assertThat(outbounds).extracting(row -> row.get("name")).containsExactly(
                "直连", "阻断", "VLESS_REALITY 45.62.112.20:443", "DNS 8.8.8.8,1.1.1.1:53", "SHADOW_SOCKS ss.example.com:18082");
        assertThat(outbounds).extracting(row -> row.get("kind")).containsExactly(
                "DIRECT", "BLOCK", "PROXY", "DNS_UPSTREAM", "PROXY");
        assertThat(outbounds.get(2)).containsEntry("password", "uuid-secret").containsEntry("config_json", "{\"flow\":\"x\"}");
        assertThat(outbounds.get(4)).containsEntry("username", "user").containsEntry("password", "pw");

        Map<Long, Map<String, Object>> routes = new java.util.HashMap<>();
        jdbc.queryForList("SELECT id, action, outbound_id, rewrite_target_host, rewrite_target_port FROM routes")
                .forEach(row -> routes.put(((Number) row.get("id")).longValue(), row));
        assertRoute(routes.get(1L), "FORWARD", 1, null, null);
        assertRoute(routes.get(2L), "FORWARD", 2, null, null);
        assertRoute(routes.get(3L), "FORWARD", 3, null, null);
        assertRoute(routes.get(4L), "FORWARD", 3, null, null);
        assertRoute(routes.get(5L), "FORWARD", 4, null, null);
        assertRoute(routes.get(6L), "FORWARD", 5, null, null);
        assertRoute(routes.get(7L), "REWRITE_DESTINATION", 1, "192.168.10.85", 8096);
        assertRoute(routes.get(8L), "REWRITE_DNS", null, "192.168.10.1", null);
        assertRoute(routes.get(9L), "FORWARD", 1, null, null);
        // Legacy columns stay frozen for rollback.
        assertThat(jdbc.queryForObject("SELECT outbound_proxy_host FROM routes WHERE id = 7", String.class))
                .isEqualTo("192.168.10.85");
        dataSource.destroy();
    }

    private static void assertRoute(Map<String, Object> row, String action, Integer outboundId, String host, Integer port) {
        assertThat(row.get("action")).isEqualTo(action);
        assertThat(row.get("outbound_id") == null ? null : ((Number) row.get("outbound_id")).intValue()).isEqualTo(outboundId);
        assertThat(row.get("rewrite_target_host")).isEqualTo(host);
        assertThat(row.get("rewrite_target_port") == null ? null : ((Number) row.get("rewrite_target_port")).intValue())
                .isEqualTo(port);
    }

    private static Flyway flyway(SingleConnectionDataSource dataSource, String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load();
    }

    private static void insertRoute(JdbcTemplate jdbc, long id, String policy, String type, String host, Integer port,
                                    String username, String password, String configJson) {
        jdbc.update("INSERT INTO routes (id, name, rules_json, policy, outbound_proxy_type, outbound_proxy_host, "
                        + "outbound_proxy_port, outbound_proxy_username, outbound_proxy_password, outbound_proxy_config_json, "
                        + "created_at, updated_at) VALUES (?, ?, '[]', ?, ?, ?, ?, ?, ?, ?, " + NOW + ", " + NOW + ")",
                id, "route-" + id, policy, type, host, port, username, password, configJson);
    }
}
