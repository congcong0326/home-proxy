package org.congcong.controlmanager.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.congcong.common.dto.WorkerMetricsDTO;
import org.congcong.common.telemetry.*;
import org.congcong.controlmanager.clickhouse.ClickHouseJdbcClient;
import org.congcong.controlmanager.entity.WorkerStatus;
import org.congcong.controlmanager.repository.WorkerStatusRepository;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OverviewServiceTest {
    private final ClickHouseJdbcClient client = mock(ClickHouseJdbcClient.class);
    private final TelemetryStore store = mock(TelemetryStore.class);
    private final WorkerStatusRepository workers = mock(WorkerStatusRepository.class);
    private final OverviewService service = new OverviewService(client, store, workers);
    private OverviewQuery query() {
        var q = new OverviewQuery(); q.setWorkerId("edge");
        q.setFrom(Instant.parse("2026-10-09T00:00:00Z")); q.setTo(q.getFrom().plusSeconds(600)); return q;
    }
    @Test
    void aggregationUsesCompleteDeduplicatedFactsAndPreservesLargeCounters() {
        var queries = new ArrayList<String>();
        when(client.query(anyString(), any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0); queries.add(sql);
            if (sql.contains("AS upload") && sql.contains("bucket_index")) return List.of(Map.of("bucket_index", 0,
                    "upload", "9007199254740993", "download", "7"));
            return List.of();
        });
        var q = query(); q.setUsername("failed-account"); q.setClientIp("192.168.1.2");
        var result = service.overview(q);
        var traffic = (Map<?, ?>) result.get("traffic");
        assertEquals("9007199254741000", traffic.get("total"));
        assertTrue(queries.stream().allMatch(sql -> sql.contains(" FINAL WHERE ")));
        assertTrue(queries.stream().noneMatch(sql -> sql.contains("default.access_log")));
        String auth = queries.stream().filter(sql -> sql.contains("credential_auth_log_v1")).findFirst().orElseThrow();
        assertTrue(auth.contains("attempted_username = ?"));
        assertFalse(auth.contains("traffic_category"));
        String dns = queries.stream().filter(sql -> sql.contains("dns_query_log_v1")).findFirst().orElseThrow();
        assertFalse(dns.contains("traffic_category"));
        assertTrue(dns.contains("response_sent = false"));
        String concurrency = queries.stream().filter(sql -> sql.startsWith("WITH intervals")).findFirst().orElseThrow();
        assertTrue(concurrency.contains("connected_at < ? AND ended_at > ?"));
        assertFalse(concurrency.contains("occurred_at >="));
    }
    @Test
    void rejectsInvalidScopeBeforeQueryAndDoesNotSilentlyFallbackWhenDisabled() {
        var q = query(); q.setTo(q.getFrom().plusSeconds(31 * 86400L + 1));
        assertThrows(IllegalArgumentException.class, () -> service.overview(q)); verifyNoInteractions(client, store);
        doThrow(new IllegalStateException("QUERY_DISABLED")).when(store).requireQueryAvailable();
        assertThrows(IllegalStateException.class, () -> service.overview(query())); verifyNoInteractions(client);
    }
    @Test
    void currentCountsUseCategoryAndSourceButNeverFinalOutcome() throws Exception {
        var metrics = new WorkerMetricsDTO();
        metrics.setEstablishedConnectionsV1(new EstablishedConnectionsV1("instance", Instant.now(),
                Map.of(TrafficCategory.EXTERNAL, 5L, TrafficCategory.INTERNAL_REWRITE, 2L),
                List.of(new EstablishedConnectionsV1.Group(TrafficCategory.EXTERNAL, "alice", "192.168.1.2", 1L, 3),
                        new EstablishedConnectionsV1.Group(TrafficCategory.EXTERNAL, "bob", "192.168.1.3", 1L, 2)), false));
        install(metrics);
        var q = query(); assertEquals("5", service.live(q).get("count"));
        q.setUsername("alice"); q.setTrafficResult("FAILURE");
        assertEquals("3", service.live(q).get("count"));
        q.setClientIp("192.168.1.3"); assertEquals("0", service.live(q).get("count"));
    }
    @Test
    void staleOrTruncatedMetricsNeverAppearAsZeroConnections() throws Exception {
        var metrics = new WorkerMetricsDTO();
        metrics.setEstablishedConnectionsV1(new EstablishedConnectionsV1("instance", Instant.now().minusSeconds(60),
                Map.of(), List.of(), false)); install(metrics);
        assertEquals("STALE", service.live(query()).get("reason"));
        metrics.setEstablishedConnectionsV1(new EstablishedConnectionsV1("instance", Instant.now(),
                Map.of(TrafficCategory.EXTERNAL, 300L), List.of(), true)); install(metrics);
        var q = query(); assertEquals("300", service.live(q).get("count"));
        q.setUsername("alice"); assertEquals("GROUPS_TRUNCATED", service.live(q).get("reason"));
    }
    private void install(WorkerMetricsDTO metrics) throws Exception {
        var status = new WorkerStatus(); status.setWorkerId("edge");
        status.setMetricsJson(new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(metrics));
        when(workers.findById("edge")).thenReturn(Optional.of(status));
    }
}
