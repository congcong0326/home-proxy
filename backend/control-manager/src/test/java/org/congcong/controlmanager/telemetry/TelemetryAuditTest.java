package org.congcong.controlmanager.telemetry;

import org.congcong.controlmanager.clickhouse.ClickHouseJdbcClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TelemetryAuditTest {
    private final ClickHouseJdbcClient client = mock(ClickHouseJdbcClient.class);
    private final TelemetryProperties properties = new TelemetryProperties();
    private final CredentialGeoAnalysis geo = mock(CredentialGeoAnalysis.class);
    private final TelemetryStore store = new TelemetryStore(client, properties, geo);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TelemetryController(store, properties)).build();

    private TelemetryQueryRequest request() {
        var request = new TelemetryQueryRequest();
        request.setWorkerId("home-01");
        request.setFrom(Instant.parse("2026-10-09T00:00:00Z"));
        request.setTo(Instant.parse("2026-10-10T00:00:00Z"));
        return request;
    }

    @Test
    void historicalUnavailableAuthLocationIsRefreshedForPageDetailAndLegacyQueryWithoutWrites() {
        String attempt = UUID.randomUUID().toString();
        String raw = "{\"authAttemptId\":\"" + attempt + "\",\"clientIp\":\"8.8.8.8\"}";
        when(client.query(startsWith("SELECT count()"), any(Object[].class))).thenReturn(List.of(Map.of("total", 1)));
        when(client.query(startsWith("SELECT raw_json"), any(Object[].class))).thenReturn(List.of(Map.of(
                "raw_json", raw, "received_at", "2026-10-09 00:00:00",
                "analysis_json", "{\"geoLookupStatus\":\"DB_UNAVAILABLE\",\"riskTags\":[\"LOCATION_UNKNOWN\"]}")));
        var resolved = new org.congcong.common.telemetry.CredentialAuthAnalysisV1();
        resolved.setGeoLookupStatus("RESOLVED"); resolved.setSourceCountry("美国"); resolved.setRiskTags(List.of());
        when(geo.analyze(any())).thenReturn(resolved);
        var page = store.page(TelemetrySchema.AUTH, request());
        var detail = store.detail(TelemetrySchema.AUTH, attempt, request());
        var legacy = store.query(TelemetrySchema.AUTH, "home-01", request().getFrom(), request().getTo(), 100, 0, null);
        for (var row : List.of(page.items().get(0), detail, legacy.get(0))) {
            var analysis = (Map<?, ?>) row.get("analysis");
            assertEquals("RESOLVED", analysis.get("geoLookupStatus"));
            assertEquals("美国", analysis.get("sourceCountry"));
            assertEquals(List.of(), analysis.get("riskTags"));
            assertEquals("8.8.8.8", row.get("clientIp"));
        }
        verify(client, never()).execute(startsWith("INSERT"), any(Object[].class));
        verify(client, never()).batchExecute(anyString(), anyList());
    }

    @Test
    void countAndPageShareServerFiltersAndNeverInterpolateUserText() {
        properties.setQueryEnabled(true);
        when(client.query(startsWith("SELECT count()"), any(Object[].class))).thenReturn(List.of(Map.of("total", 123L)));
        when(client.query(startsWith("SELECT raw_json"), any(Object[].class))).thenReturn(List.of());
        var request = request();
        request.setUsername("' OR 1=1 --"); request.setTarget("nas.test"); request.setRoute("NAS");
        request.setTrafficCategory("INTERNAL_REWRITE"); request.setClientIp("192.168.1.1");
        request.setOutcome("ERROR_END"); request.setConnectOutcome("CONNECTED"); request.setProtocol("SOCKS5"); request.setOffset(50);
        var page = store.page(TelemetrySchema.SESSIONS, request);
        assertEquals("123", page.total()); assertEquals(50, page.offset()); assertEquals("[from,to)", page.timeBoundary());
        var sql = ArgumentCaptor.forClass(String.class); var params = ArgumentCaptor.forClass(Object[].class);
        verify(client, times(2)).query(sql.capture(), params.capture());
        String countSql = sql.getAllValues().get(0);
        String countWhere = countSql.substring(countSql.indexOf(" FINAL WHERE ") + " FINAL WHERE ".length());
        assertTrue(sql.getAllValues().get(1).contains(" FINAL WHERE " + countWhere + " ORDER BY occurred_at DESC, record_id LIMIT ? OFFSET ?"));
        assertFalse(sql.getAllValues().get(1).contains(request.getUsername()));
        assertTrue(List.of(params.getAllValues().get(1)).contains(request.getUsername()));
        assertEquals(params.getAllValues().get(0).length + 2, params.getAllValues().get(1).length);
    }

    @Test
    void dnsAssociationUsesExplicitParentConnectionAndIsNotAddressCorrelation() {
        properties.setQueryEnabled(true);
        when(client.query(startsWith("SELECT count()"), any(Object[].class))).thenReturn(List.of(Map.of("total", 0)));
        when(client.query(startsWith("SELECT raw_json"), any(Object[].class))).thenReturn(List.of());
        var request = request(); request.setConnectionId(UUID.randomUUID().toString()); request.setQtype("AAAA");
        store.page(TelemetrySchema.DNS, request);
        verify(client).query(contains("(connection_id = ? OR parent_connection_id = ?) AND qtype = ?"),
                eq(new Object[]{"home-01", java.sql.Timestamp.from(request.getFrom()), java.sql.Timestamp.from(request.getTo()), request.getConnectionId(), request.getConnectionId(), "AAAA"}));
    }

    @Test
    void authFilteringIncludesAttemptedUsernameAndDetailKeepsLargeIntegersAndNulls() {
        properties.setQueryEnabled(true);
        var request = request(); request.setUsername("unknown-user"); request.setAuthMethod("HTTP_BASIC");
        when(client.query(startsWith("SELECT count()"), any(Object[].class))).thenReturn(List.of(Map.of("total", 1)));
        when(client.query(startsWith("SELECT raw_json"), any(Object[].class))).thenReturn(List.of(Map.of(
                "raw_json", "{\"authDurationUs\":\"9007199254740993\",\"userId\":null}", "received_at", "2026-10-09 00:00:00")));
        var page = store.page(TelemetrySchema.AUTH, request);
        assertEquals("9007199254740993", page.items().get(0).get("authDurationUs"));
        assertNull(page.items().get(0).get("userId"));
        verify(client, times(2)).query(contains("ifNull(attempted_username,''), ?"), any(Object[].class));
        var detail = store.detail(TelemetrySchema.AUTH, UUID.randomUUID().toString(), request());
        assertEquals("9007199254740993", detail.get("authDurationUs"));
    }

    @Test
    void sessionDetailAssociatesSubmittedNamesWithoutClaimingVerifiedIdentityOrWritingFacts() {
        properties.setQueryEnabled(true);
        String connection = UUID.randomUUID().toString(), instance = UUID.randomUUID().toString();
        when(client.query(startsWith("SELECT raw_json"), any(Object[].class))).thenReturn(List.of(Map.of(
                "raw_json", "{\"connectionId\":\"" + connection + "\",\"workerInstanceId\":\"" + instance + "\",\"username\":null,\"userId\":null}",
                "received_at", "2026-10-09 01:00:00")));
        when(client.query(startsWith("SELECT worker_instance_id"), any(Object[].class))).thenReturn(List.of(
                Map.of("worker_instance_id", instance, "connection_id", connection, "auth_attempt_id", "attempt-1",
                        "attempted_username", "nonexistent-account", "auth_outcome", "FAILURE", "auth_method", "SOCKS5_PASSWORD", "occurred_at", "2026-10-09 00:59:59"),
                Map.of("worker_instance_id", instance, "connection_id", connection, "auth_attempt_id", "attempt-2",
                        "attempted_username", "second-account", "auth_outcome", "DENIED", "auth_method", "SOCKS5_PASSWORD", "occurred_at", "2026-10-09 00:59:58")));
        var detail = store.detail(TelemetrySchema.SESSIONS, connection, request());
        assertNull(detail.get("username")); assertNull(detail.get("userId"));
        var attempts = (List<Map<String, Object>>) detail.get("credentialAuthAttempts");
        assertEquals(List.of("nonexistent-account", "second-account"), attempts.stream().map(a -> a.get("attemptedUsername")).toList());
        assertEquals(false, detail.get("credentialAuthAttemptsTruncated"));
        verify(client).query(contains("(worker_instance_id, connection_id) IN ((?,?))"),
                eq(new Object[]{"home-01", java.sql.Timestamp.from(request().getTo().minusSeconds(31 * 86400L)),
                        java.sql.Timestamp.from(request().getTo()), instance, connection}));
        verify(client, never()).execute(startsWith("INSERT"), any(Object[].class));
    }

    @Test
    void attemptedNameSearchIsAppliedToBothCountAndSessionPageWithWorkerAndTimeBounds() {
        properties.setQueryEnabled(true);
        when(client.query(startsWith("SELECT count()"), any(Object[].class))).thenReturn(List.of(Map.of("total", 1)));
        when(client.query(startsWith("SELECT raw_json"), any(Object[].class))).thenReturn(List.of());
        var request = request(); request.setUsername("unknown-account");
        store.page(TelemetrySchema.SESSIONS, request);
        var sql = ArgumentCaptor.forClass(String.class); var params = ArgumentCaptor.forClass(Object[].class);
        verify(client, times(2)).query(sql.capture(), params.capture());
        for (String query : sql.getAllValues()) {
            assertTrue(query.contains("SELECT worker_instance_id, connection_id FROM telemetry.credential_auth_log_v1 FINAL WHERE worker_id = ? AND occurred_at >= ? AND occurred_at < ?"));
            assertFalse(query.contains("unknown-account"));
        }
        assertEquals(2, List.of(params.getAllValues().get(0)).stream().filter("unknown-account"::equals).count());
    }

    @Test
    void queryBoundsAndUnsupportedFiltersAreRejectedBeforeDatabaseAccess() {
        properties.setQueryEnabled(true);
        var request = request(); request.setOffset(10001);
        assertThrows(IllegalArgumentException.class, () -> store.page(TelemetrySchema.EVENTS, request));
        request.setOffset(0); request.setLimit(201);
        assertThrows(IllegalArgumentException.class, () -> store.page(TelemetrySchema.EVENTS, request));
        request.setLimit(50); request.setTarget("domain.test");
        assertThrows(IllegalArgumentException.class, () -> store.page(TelemetrySchema.EVENTS, request));
        request.setTarget(null); request.setTo(request.getFrom().plusSeconds(31 * 86400L + 1));
        assertThrows(IllegalArgumentException.class, () -> store.page(TelemetrySchema.EVENTS, request));
        verifyNoInteractions(client);
    }

    @Test
    void httpContractBindsUtcScopeAndDistinguishesDisabledMissingAndInvalid() throws Exception {
        mvc.perform(get("/api/observability/v1/dns/page").param("workerId", "home-01")
                .param("from", "2026-10-09T00:00:00Z").param("to", "2026-10-10T00:00:00Z")).andExpect(status().isServiceUnavailable());
        properties.setQueryEnabled(true);
        when(client.query(startsWith("SELECT count()"), any(Object[].class))).thenReturn(List.of(Map.of("total", 0)));
        when(client.query(startsWith("SELECT raw_json"), any(Object[].class))).thenReturn(List.of());
        mvc.perform(get("/api/observability/v1/dns/page").param("workerId", "home-01")
                .param("from", "2026-10-09T00:00:00Z").param("to", "2026-10-10T00:00:00Z"))
                .andExpect(status().isOk()).andExpect(jsonPath("total").value("0")).andExpect(jsonPath("items").isEmpty());
        mvc.perform(get("/api/observability/v1/events/" + UUID.randomUUID()).param("workerId", "home-01")
                .param("from", "2026-10-09T00:00:00Z").param("to", "2026-10-10T00:00:00Z")).andExpect(status().isNotFound());
        mvc.perform(get("/api/observability/v1/events/page").param("workerId", "home-01")
                .param("from", "2026-10-09T00:00:00Z").param("to", "2026-10-10T00:00:00Z").param("limit", "201")).andExpect(status().isBadRequest());
    }
}
