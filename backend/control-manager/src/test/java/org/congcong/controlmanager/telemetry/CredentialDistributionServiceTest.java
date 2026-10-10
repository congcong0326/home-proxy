package org.congcong.controlmanager.telemetry;

import org.congcong.common.telemetry.*;
import org.congcong.controlmanager.clickhouse.ClickHouseJdbcClient;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CredentialDistributionServiceTest {
    private final ClickHouseJdbcClient client = mock(ClickHouseJdbcClient.class);
    private final TelemetryStore store = mock(TelemetryStore.class);
    private final CredentialGeoAnalysis geo = mock(CredentialGeoAnalysis.class);
    private final CredentialDistributionService service = new CredentialDistributionService(client, store, geo);

    private DistributionQuery query() {
        var query = DistributionServiceTest.query();
        query.setDimension("auth_geo"); query.setMetric("authentications");
        return query;
    }
    private Map<String, Object> row(String ip, String count, String success, String failure) {
        return Map.of("ip", ip, "attempts", count, "success", success, "failure", failure);
    }
    private CredentialAuthAnalysisV1 location(String country, String region, String city, String status) {
        var value = new CredentialAuthAnalysisV1(); value.setSourceCountry(country); value.setSourceRegion(region);
        value.setSourceCity(city); value.setGeoLookupStatus(status); return value;
    }

    @Test
    void mergesTriplesNormalizesMissingFieldsAndIncludesEveryAttemptBeforeTopN() {
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of(
                row("1.1.1.1", "18446744073709551620", "2", "3"), row("1.0.0.1", "5", "4", "1"),
                row("8.8.8.8", "4", "1", "2"), row("10.0.0.1", "2", "2", "0"), row("9.9.9.9", "3", "0", "3")));
        when(geo.analyze(any())).thenAnswer(invocation -> {
            String ip = ((CredentialAuthLogV1) invocation.getArgument(0)).getClientIp();
            return switch (ip) {
                case "1.1.1.1" -> location(" 中国 ", null, "", "RESOLVED");
                case "1.0.0.1" -> location("中国", " ", null, "RESOLVED");
                case "8.8.8.8" -> location("中国", "广东", "深圳", "RESOLVED");
                case "10.0.0.1" -> location(null, null, null, "PRIVATE");
                default -> location(null, null, null, "DB_UNAVAILABLE");
            };
        });
        var query = query(); query.setLimit(1);
        var result = service.aggregate(query);
        assertEquals("18446744073709551634", result.totalValue());
        assertEquals("9", result.otherValue()); assertEquals("4", result.groups());
        assertEquals(List.of("中国", "", ""), result.items().get(0).keys());
        assertEquals("18446744073709551625", result.items().get(0).value());
        assertEquals("6", result.items().get(0).auth().success());
        assertEquals(2, result.items().get(0).sources().size());
        query.setLimit(10);
        result = service.aggregate(query);
        assertEquals("0", result.otherValue());
        assertEquals(Map.of("DB_UNAVAILABLE", "3"), result.items().stream().filter(item -> item.keys().get(0).equals("__UNKNOWN__")).findFirst().orElseThrow().unknownReasons());
        assertTrue(result.items().stream().anyMatch(item -> item.keys().get(0).equals("__PRIVATE__")));
        assertEquals("AUTH_ATTEMPTS_ONLY", result.historyBasis());
    }

    @Test
    void filtersAuthenticationFactsWithParametersAndNeverSessionTraffic() {
        when(client.query(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            assertTrue(sql.contains("credential_auth_log_v1 FINAL"));
            assertTrue(sql.contains("auth_outcome IN ('FAILURE','DENIED','MALFORMED','ERROR')"));
            assertFalse(sql.contains("traffic_category")); assertFalse(sql.contains("LIMIT"));
            assertFalse(sql.contains("alice'"));
            var params = Arrays.asList((Object[]) invocation.getRawArguments()[1]);
            assertEquals(List.of("alice'", "alice'", "alice'", "8.8.8.8"), params.subList(3, 7));
            return List.of();
        });
        var query = query(); query.setAuthOutcome("FAILURE"); query.setUsername("alice'"); query.setClientIp("8.8.8.8");
        var result = service.aggregate(query);
        assertEquals("0", result.totalValue()); assertTrue(result.items().isEmpty());
        query.setMetric("bytes"); assertThrows(IllegalArgumentException.class, () -> service.aggregate(query));
        query.setMetric("authentications"); query.setAuthOutcome("FAILED");
        assertThrows(IllegalArgumentException.class, () -> service.aggregate(query));
    }
}
