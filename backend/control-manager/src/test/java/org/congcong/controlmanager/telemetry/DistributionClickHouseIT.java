package org.congcong.controlmanager.telemetry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.congcong.controlmanager.clickhouse.ClickHouseJdbcClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt-in, read-only ClickHouse 24.8 SQL verification. No tables or stored logs are modified. */
@EnabledIfEnvironmentVariable(named = "DISTRIBUTION_CLICKHOUSE_CONTAINER", matches = ".+")
class DistributionClickHouseIT {
    private static final String FIXTURE = "(SELECT * FROM values('worker_id String, occurred_at DateTime64(6), "
            + "user_id Nullable(UInt64), username Nullable(String), logical_target_host Nullable(String), "
            + "route_id Nullable(UInt64), route_name Nullable(String), client_ip Nullable(String), "
            + "traffic_category Nullable(String), connect_outcome String, upload_bytes UInt64, download_bytes UInt64', "
            + "('home-01','2026-10-09 01:00:00',42,'alice','a.test',1,'direct','192.168.1.2','EXTERNAL','CONNECTED',18446744073709551615,10),"
            + "('home-01','2026-10-09 02:00:00',42,'alice','b.test',1,'direct','192.168.1.2','EXTERNAL','CONNECTED',18446744073709551615,10),"
            + "('home-01','2026-10-09 03:00:00',7,'bob','b.test',2,'rewrite','192.168.1.3','INTERNAL_REWRITE','CONNECTED',100,200),"
            + "('home-01','2026-10-09 04:00:00',NULL,NULL,NULL,NULL,NULL,NULL,NULL,'NOT_ATTEMPTED',0,0),"
            + "('other','2026-10-09 05:00:00',42,'alice','other.test',1,'direct','192.168.1.2','EXTERNAL','CONNECTED',999,999),"
            + "('home-01','2026-11-01 00:00:00',42,'alice','future.test',1,'direct','192.168.1.2','EXTERNAL','CONNECTED',999,999)))";

    @Test
    void realEnginePreservesDenominatorsLargeSumsAndUnknownsForAllDimensionsAndMetrics() throws Exception {
        var client = mock(ClickHouseJdbcClient.class);
        var service = new DistributionService(client, mock(TelemetryStore.class));
        when(client.query(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            sql = sql.replace(TelemetrySchema.SESSIONS.table + " FINAL", FIXTURE);
            return execute(bind(sql, (Object[]) invocation.getRawArguments()[1]));
        });
        for (String dimension : List.of("users", "targets", "user_targets", "routes", "client_ips", "categories")) {
            for (String metric : List.of("connections", "bytes", "upload", "download")) {
                var request = DistributionServiceTest.query();
                request.setTrafficCategory("ALL"); request.setDimension(dimension); request.setMetric(metric); request.setLimit(1);
                var result = service.aggregate(request);
                assertEquals("4", result.totals().connections(), dimension + "/" + metric);
                assertEquals("36893488147419103550", result.totals().bytes());
                assertEquals("1", result.missingConnections());
                assertEquals(1, result.items().size());
                assertEquals(new java.math.BigInteger(result.totalValue()),
                        new java.math.BigInteger(result.items().get(0).value()).add(new java.math.BigInteger(result.otherValue())));
            }
        }
        var request = DistributionServiceTest.query();
        request.setTrafficCategory("UNKNOWN");
        assertEquals("1", service.aggregate(request).totals().connections());
        request.setUsername("alice' OR 1=1 --");
        assertEquals("0", service.aggregate(request).totals().connections());
    }

    @Test
    void storedHistoryUsesFinalAndMatchesIndependentReadOnlyCount() throws Exception {
        var client = mock(ClickHouseJdbcClient.class);
        var service = new DistributionService(client, mock(TelemetryStore.class));
        when(client.query(anyString(), any(Object[].class))).thenAnswer(invocation ->
                execute(bind(invocation.getArgument(0), (Object[]) invocation.getRawArguments()[1])));
        var request = DistributionServiceTest.query(); request.setTrafficCategory("ALL");
        var result = service.aggregate(request);
        var reference = execute("SELECT toString(count()) AS connections FROM " + TelemetrySchema.SESSIONS.table
                + " FINAL WHERE worker_id = 'home-01' AND occurred_at >= '2026-10-01' AND occurred_at < '2026-11-01'");
        assertEquals(reference.get(0).get("connections"), result.totals().connections());
    }

    private static String bind(String sql, Object[] params) {
        for (Object param : params) {
            String value = param instanceof Timestamp time ? time.toInstant().toString().replace("T", " ").replace("Z", "") : param.toString();
            String literal = param instanceof Number ? value : "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
            int index = sql.indexOf('?');
            sql = sql.substring(0, index) + literal + sql.substring(index + 1);
        }
        return sql;
    }

    private static List<Map<String, Object>> execute(String sql) throws Exception {
        var process = new ProcessBuilder("docker", "exec", "-i", System.getenv("DISTRIBUTION_CLICKHOUSE_CONTAINER"),
                "sh", "-c", "exec clickhouse-client --user \"$CLICKHOUSE_USER\" --password \"$CLICKHOUSE_PASSWORD\" --format JSON")
                .redirectErrorStream(true).start();
        try (var input = process.getOutputStream()) { input.write(sql.getBytes(StandardCharsets.UTF_8)); }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(15, TimeUnit.SECONDS), "ClickHouse query timed out");
        assertEquals(0, process.exitValue(), output);
        var mapper = new ObjectMapper();
        return mapper.convertValue(mapper.readTree(output).get("data"), new TypeReference<List<Map<String, Object>>>() { });
    }
}
