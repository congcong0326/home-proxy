package org.congcong.controlmanager.service.mail.inspection;

import org.congcong.common.telemetry.CredentialAuthAnalysisV1;
import org.congcong.common.telemetry.CredentialAuthLogV1;
import org.congcong.controlmanager.clickhouse.ClickHouseJdbcClient;
import org.congcong.controlmanager.telemetry.CredentialGeoAnalysis;
import org.congcong.controlmanager.telemetry.TelemetryStore;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthLocationInspectionServiceTest {
    private final ClickHouseJdbcClient client = mock(ClickHouseJdbcClient.class);
    private final TelemetryStore store = mock(TelemetryStore.class);
    private final CredentialGeoAnalysis geo = mock(CredentialGeoAnalysis.class);
    private final InspectionProperties properties = new InspectionProperties();
    private final AuthLocationInspectionService service = new AuthLocationInspectionService(client, store, geo, properties);
    private final Instant from = Instant.parse("2026-10-09T01:00:00Z"), to = Instant.parse("2026-10-10T01:00:00Z");

    private Map<String, Object> row(String id, String account, String ip, String count, String days) {
        return Map.of("account_id", id, "account", account, "ip", ip, "records", count, "days", days,
                "first_at", Timestamp.from(from), "last_at", Timestamp.from(to.minusSeconds(1)));
    }

    private CredentialAuthAnalysisV1 location(String country, String region, String city, String status) {
        var value = new CredentialAuthAnalysisV1(); value.setSourceCountry(country); value.setSourceCountryCode("CN");
        value.setSourceRegion(region); value.setSourceCity(city); value.setGeoLookupStatus(status);
        value.setGeoDatabaseVersion("2026-10-01"); return value;
    }

    @Test
    void comparesEachVerifiedAccountAgainstAnExclusiveHistoricalWindow() {
        properties.setAuthMinimumHistoryDays(1);
        var current = List.of(row("1", "alice", "1.1.1.1", "3", "['2026-10-09']"), row("2", "bob", "2.2.2.2", "1", "['2026-10-09']"));
        var history = List.of(row("1", "alice", "3.3.3.3", "7", "['2026-10-08']"), row("2", "bob", "4.4.4.4", "7", "['2026-10-08']"));
        when(client.query(anyString(), any(Object[].class))).thenReturn(current, history);
        when(geo.analyze(any())).thenAnswer(call -> {
            String ip = ((CredentialAuthLogV1) call.getArgument(0)).getClientIp();
            return location("中国", ip.equals("3.3.3.3") ? "江苏" : "浙江", ip.equals("3.3.3.3") ? "南京" : "杭州", "RESOLVED");
        });
        var report = service.inspect(List.of("default"), from, to, ZoneId.of("Asia/Shanghai"));
        assertTrue(report.items().stream().anyMatch(item -> item.title().startsWith("alice")
                && item.fields().getOrDefault("位置变化", "").contains("新增成功认证地区")));
        assertTrue(report.items().stream().anyMatch(item -> item.title().startsWith("bob")
                && item.fields().getOrDefault("位置变化", "").equals("未观察到新增位置")));
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        var params = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(client, times(2)).query(sql.capture(), params.capture());
        assertTrue(sql.getAllValues().stream().allMatch(query -> query.contains("auth_outcome = 'SUCCESS'") && query.contains("FINAL")));
        assertEquals(List.of("Asia/Shanghai", "default", Timestamp.from(from), Timestamp.from(to)), Arrays.asList(params.getAllValues().get(0)));
        assertEquals(List.of("Asia/Shanghai", "default", Timestamp.from(from.minus(30, ChronoUnit.DAYS)), Timestamp.from(from)), Arrays.asList(params.getAllValues().get(1)));
    }

    @Test
    void insufficientHistoryIsNotPresentedAsNoDriftAndPrivateUnknownAreSeparated() {
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of(
                row("1", "alice", "1.1.1.1", "18446744073709551620", "['2026-10-09']"),
                row("1", "alice", "10.0.0.1", "2", "['2026-10-09']"),
                row("1", "alice", "9.9.9.9", "3", "['2026-10-09']")), List.of());
        when(geo.analyze(any())).thenAnswer(call -> {
            String ip = ((CredentialAuthLogV1) call.getArgument(0)).getClientIp();
            return location("中国", "江苏", "南京", ip.equals("10.0.0.1") ? "PRIVATE" : ip.equals("9.9.9.9") ? "DB_UNAVAILABLE" : "RESOLVED");
        });
        var report = service.inspect(List.of("default"), from, to, ZoneId.of("UTC"));
        assertEquals("18446744073709551625", report.items().get(0).fields().get("成功认证次数"));
        assertEquals("2", report.items().get(0).fields().get("内网来源次数"));
        assertEquals("3", report.items().get(0).fields().get("位置未知次数"));
        var changes = report.items().stream().map(item -> item.fields().getOrDefault("位置变化", "")).toList();
        assertTrue(changes.contains("基线不足，暂不判断偏移"));
        assertTrue(changes.contains("位置未知，无法判断偏移"));
        assertTrue(changes.stream().noneMatch(change -> change.equals("未观察到新增位置")));
    }

    @Test
    void partialGeographyDoesNotInventRegionChangesAndCityChangesStayInformational() {
        var old = new AuthLocationInspectionService.Location("CN", "中国", "江苏", "南京", "RESOLVED", "v1");
        var missing = new AuthLocationInspectionService.Location("CN", "中国", "", "", "RESOLVED", "v1");
        var city = new AuthLocationInspectionService.Location("CN", "中国", "江苏", "苏州", "RESOLVED", "v1");
        assertTrue(AuthLocationInspectionService.compare(missing, Set.of(old), true).contains("地区未知"));
        assertEquals("城市变化（仅供参考）", AuthLocationInspectionService.compare(city, Set.of(old), true));
    }

    @Test
    void missingRegionDoesNotCountAsAnAdditionalRegionOrAnAccountRename() {
        properties.setAuthMinimumHistoryDays(1);
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of(
                row("1", "new-name", "1.1.1.1", "1", "['2026-10-09']"),
                row("1", "new-name", "2.2.2.2", "1", "['2026-10-09']")),
                List.of(row("1", "old-name", "3.3.3.3", "1", "['2026-10-08']")));
        when(geo.analyze(any())).thenAnswer(call -> location("中国",
                ((CredentialAuthLogV1) call.getArgument(0)).getClientIp().equals("1.1.1.1") ? "" : "江苏", "", "RESOLVED"));
        var report = service.inspect(List.of("default"), from, to, ZoneId.of("UTC"));
        var account = report.items().stream().filter(item -> item.title().startsWith("账号 ")).findFirst().orElseThrow();
        assertTrue(account.title().contains("new-name"));
        assertEquals("未观察到多个已定位国家 / 地区", account.fields().get("本窗口跨地区情况"));
    }
}
