package org.congcong.controlmanager.telemetry;

import org.congcong.common.telemetry.TrafficCategory;
import org.congcong.controlmanager.clickhouse.ClickHouseJdbcClient;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InboundTrafficServiceTest {
    private final ClickHouseJdbcClient client = mock(ClickHouseJdbcClient.class);
    private final TelemetryStore store = mock(TelemetryStore.class);
    private final InboundTrafficService service = new InboundTrafficService(client, store);

    @Test
    void aggregatesAllCategoriesWithoutLosingPrecisionAndScopesCoverageToInboundAndWorker() {
        var queries = new ArrayList<String>();
        var parameters = new ArrayList<List<Object>>();
        when(client.query(anyString(), any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            queries.add(sql);
            parameters.add(Arrays.asList(Arrays.copyOfRange(call.getArguments(), 1, call.getArguments().length)));
            if (sql.contains("GROUP BY")) return List.of(
                    Map.of("category", "EXTERNAL", "upload", "18446744073709551616", "download", "7"),
                    Map.of("category", "INTERNAL_REWRITE", "upload", "3", "download", "11"),
                    Map.of("category", "UNKNOWN", "upload", "1", "download", "2"));
            return List.of(Map.of("first_at", "2026-10-02 00:00:00"));
        });
        var result = service.monthly(42, "home-01", "2026-10", "Asia/Shanghai");
        assertEquals("18446744073709551620", result.traffic().upload());
        assertEquals("20", result.traffic().download());
        assertEquals("18446744073709551640", result.traffic().total());
        assertEquals("14", result.categories().get(TrafficCategory.INTERNAL_REWRITE).total());
        assertEquals("0", result.categories().get(TrafficCategory.OTHER_INTERNAL).total());
        assertEquals("3", result.categories().get(TrafficCategory.UNKNOWN).total());
        assertEquals("2026-10-02 00:00:00", result.firstSessionAt());
        assertEquals("2026-09-30T16:00:00Z", result.from());
        assertEquals("2026-10-31T16:00:00Z", result.to());
        assertTrue(queries.stream().allMatch(sql -> sql.contains("telemetry.proxy_session_log_v1 FINAL WHERE worker_id = ? AND inbound_id = ?")));
        assertTrue(queries.get(0).contains("occurred_at >= ? AND occurred_at < ?"));
        assertTrue(queries.get(0).contains("toUInt128"));
        assertEquals(List.of("home-01", 42L, Timestamp.from(Instant.parse(result.from())), Timestamp.from(Instant.parse(result.to()))), parameters.get(0));
        assertEquals(List.of("home-01", 42L), parameters.get(1));
        verify(store).requireQueryAvailable();
    }

    @Test
    void emptyNewHistoryReturnsExplicitZeroCategoriesAndNoCoverageStart() {
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of());
        var result = service.monthly(1, "home-01", "2026-10", "UTC");
        assertEquals("0", result.traffic().total());
        assertEquals(4, result.categories().size());
        assertNull(result.firstSessionAt());
    }

    @Test
    void daylightSavingMonthUsesLocalCalendarBoundaries() {
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of());
        var result = service.monthly(1, "home-01", "2026-03", "America/New_York");
        assertEquals("2026-03-01T05:00:00Z", result.from());
        assertEquals("2026-04-01T04:00:00Z", result.to());
    }

    @Test
    void invalidInputsAndUnavailableNewQueriesNeverUseLegacyData() {
        assertThrows(IllegalArgumentException.class, () -> service.monthly(0, "home-01", null, "UTC"));
        assertThrows(IllegalArgumentException.class, () -> service.monthly(1, " ", null, "UTC"));
        assertThrows(DateTimeException.class, () -> service.monthly(1, "home-01", "2026-13", "UTC"));
        assertThrows(DateTimeException.class, () -> service.monthly(1, "home-01", null, "invalid"));
        verifyNoInteractions(client, store);
        doThrow(new IllegalStateException("QUERY_DISABLED")).when(store).requireQueryAvailable();
        assertThrows(IllegalStateException.class, () -> service.monthly(1, "home-01", null, "UTC"));
        verifyNoInteractions(client);
    }
}
