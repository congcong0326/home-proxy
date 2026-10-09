package org.congcong.controlmanager.telemetry;

import org.congcong.controlmanager.clickhouse.ClickHouseJdbcClient;
import org.junit.jupiter.api.Test;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DistributionServiceTest {
    private final ClickHouseJdbcClient client = mock(ClickHouseJdbcClient.class);
    private final TelemetryStore store = mock(TelemetryStore.class);
    private final DistributionService service = new DistributionService(client, store);

    static DistributionQuery query() {
        var query = new DistributionQuery();
        query.setWorkerId("home-01");
        query.setFrom(Instant.parse("2026-10-01T00:00:00Z"));
        query.setTo(Instant.parse("2026-11-01T00:00:00Z"));
        return query;
    }

    @Test
    void fullDenominatorAndRemainderRetainLargeIntegersAndMissingGroups() {
        var queries = new ArrayList<String>();
        var params = new ArrayList<List<Object>>();
        when(client.query(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            queries.add(invocation.getArgument(0));
            params.add(Arrays.asList((Object[]) invocation.getRawArguments()[1]));
            if (queries.size() == 2) return List.of(Map.of("first_at", "2026-10-01 01:00:00"));
            var row = new HashMap<String, Object>();
            row.put("keys_json", "[\"42\",\"alice\"]");
            row.put("connections", "3"); row.put("upload", "18446744073709551620"); row.put("download", "20");
            row.put("value", "18446744073709551640");
            row.put("total_connections", "9"); row.put("total_upload", "18446744073709551630"); row.put("total_download", "30");
            row.put("total_value", "18446744073709551660"); row.put("groups", "5"); row.put("missing_connections", "2");
            return List.of(row);
        });
        var request = query(); request.setMetric("bytes"); request.setLimit(1);
        request.setUsername("alice' OR 1=1 --"); request.setClientIp("192.168.1.2"); request.setConnectOutcome("CONNECTED");
        var result = service.aggregate(request);
        assertEquals("18446744073709551660", result.totals().bytes());
        assertEquals("18446744073709551640", result.items().get(0).counters().bytes());
        assertEquals("20", result.otherValue());
        assertEquals("2", result.missingConnections());
        assertEquals(List.of("42", "alice"), result.items().get(0).keys());
        assertEquals("[from,to)", result.timeBoundary());
        assertTrue(queries.get(0).contains("FINAL WHERE worker_id = ? AND occurred_at >= ? AND occurred_at < ?"));
        assertTrue(queries.get(0).contains("toUInt128"));
        assertTrue(queries.get(0).contains("OVER ()"));
        assertFalse(queries.get(0).contains(request.getUsername()));
        assertEquals(List.of("home-01", Timestamp.from(request.getFrom()), Timestamp.from(request.getTo()),
                "EXTERNAL", "CONNECTED", request.getUsername(), "192.168.1.2", 1), params.get(0));
        assertEquals(List.of("home-01"), params.get(1));
    }

    @Test
    void emptyRangeHasNoFakeHistoryAndAllDimensionsAreBounded() {
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of());
        for (String dimension : List.of("users", "targets", "user_targets", "routes", "client_ips", "categories")) {
            var request = query(); request.setDimension(dimension); request.setTrafficCategory("ALL");
            var result = service.aggregate(request);
            assertEquals("0", result.totalValue()); assertEquals("0", result.otherValue());
            assertEquals("0", result.totals().bytes()); assertNull(result.firstSessionAt()); assertTrue(result.items().isEmpty());
        }
    }

    @Test
    void invalidInputsAndUnavailableSourceNeverQueryLegacyData() {
        var request = query(); request.setDimension("users; DROP TABLE x");
        assertThrows(IllegalArgumentException.class, () -> service.aggregate(request));
        request.setDimension("users"); request.setMetric("src_geo");
        assertThrows(IllegalArgumentException.class, () -> service.aggregate(request));
        request.setMetric("connections"); request.setLimit(101);
        assertThrows(IllegalArgumentException.class, () -> service.aggregate(request));
        request.setLimit(10); request.setTo(request.getFrom());
        assertThrows(IllegalArgumentException.class, () -> service.aggregate(request));
        request.setTo(request.getFrom().plusSeconds(94 * 86400));
        assertThrows(IllegalArgumentException.class, () -> service.aggregate(request));
        verifyNoInteractions(client, store);
        doThrow(new IllegalStateException("QUERY_DISABLED")).when(store).requireQueryAvailable();
        assertThrows(IllegalStateException.class, () -> service.aggregate(query()));
        verifyNoInteractions(client);
    }
}
