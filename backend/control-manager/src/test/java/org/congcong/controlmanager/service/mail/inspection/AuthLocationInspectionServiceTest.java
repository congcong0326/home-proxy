package org.congcong.controlmanager.service.mail.inspection;

import org.congcong.common.telemetry.CredentialAuthAnalysisV1;
import org.congcong.common.telemetry.CredentialAuthLogV1;
import org.congcong.controlmanager.telemetry.TelemetrySqliteFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

import static org.congcong.controlmanager.telemetry.TelemetrySqliteFixture.failedAuth;
import static org.congcong.controlmanager.telemetry.TelemetrySqliteFixture.successAuth;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class AuthLocationInspectionServiceTest {
    private final Instant from = Instant.parse("2026-10-09T01:00:00Z"), to = Instant.parse("2026-10-10T01:00:00Z");
    private final InspectionProperties properties = new InspectionProperties();
    private TelemetrySqliteFixture db;
    private AuthLocationInspectionService service;

    @BeforeEach
    void setUp() throws Exception {
        db = new TelemetrySqliteFixture();
        service = new AuthLocationInspectionService(db.client, db.store, db.geo, properties);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    private CredentialAuthAnalysisV1 location(String country, String region, String city, String status) {
        var value = new CredentialAuthAnalysisV1();
        value.setSourceCountry(country);
        value.setSourceCountryCode("CN");
        value.setSourceRegion(region);
        value.setSourceCity(city);
        value.setGeoLookupStatus(status);
        value.setGeoDatabaseVersion("2026-10-01");
        return value;
    }

    private void geo(java.util.function.Function<String, CredentialAuthAnalysisV1> lookup) {
        when(db.geo.analyze(any())).thenAnswer(call -> lookup.apply(((CredentialAuthLogV1) call.getArgument(0)).getClientIp()));
    }

    @Test
    void comparesEachVerifiedAccountAgainstAnExclusiveHistoricalWindow() throws Exception {
        properties.setAuthMinimumHistoryDays(1);
        geo(ip -> location("中国", "3.3.3.3".equals(ip) ? "江苏" : "浙江", "3.3.3.3".equals(ip) ? "南京" : "杭州", "RESOLVED"));
        db.ingest("default",
                successAuth(from.plusSeconds(60), "1.1.1.1", 1L, "alice"),
                successAuth(from.plusSeconds(120), "1.1.1.1", 1L, "alice"),
                successAuth(from.plusSeconds(180), "2.2.2.2", 2L, "bob"),
                successAuth(from.minusSeconds(86400), "3.3.3.3", 1L, "alice"),
                successAuth(from.minusSeconds(86400), "4.4.4.4", 2L, "bob"),
                // Failures, other gateways and facts at the exclusive window end never count as successful locations.
                failedAuth(from.plusSeconds(240), UUID.randomUUID().toString(), "alice"),
                successAuth(to, "5.5.5.5", 1L, "alice"));
        db.ingest("other", successAuth(from.plusSeconds(60), "6.6.6.6", 1L, "alice"));
        var report = service.inspect(List.of("default"), from, to, ZoneId.of("Asia/Shanghai"));
        assertEquals("3", report.items().get(0).fields().get("成功认证次数"));
        assertTrue(report.items().stream().anyMatch(item -> item.title().startsWith("alice")
                && item.fields().get("成功次数").equals("2") && item.fields().get("来源 IP").equals("1.1.1.1")
                && item.fields().getOrDefault("位置变化", "").contains("新增成功认证地区")));
        assertTrue(report.items().stream().anyMatch(item -> item.title().startsWith("bob")
                && item.fields().getOrDefault("位置变化", "").equals("未观察到新增位置")));
        assertTrue(report.items().stream().noneMatch(item -> item.fields().getOrDefault("来源 IP", "").matches(".*(5\\.5|6\\.6).*")));
    }

    @Test
    void historyDaysAreCountedInTheInspectionTimeZone() throws Exception {
        properties.setAuthMinimumHistoryDays(2);
        geo(ip -> location("中国", "江苏", "南京", "RESOLVED"));
        // 23:30 and 00:30 Asia/Shanghai: two local days although both fall on the same UTC day.
        db.ingest("default",
                successAuth(Instant.parse("2026-10-08T15:30:00Z"), "1.1.1.1", 1L, "alice"),
                successAuth(Instant.parse("2026-10-08T16:30:00Z"), "1.1.1.1", 1L, "alice"),
                successAuth(from.plusSeconds(60), "1.1.1.1", 1L, "alice"));
        var shanghai = service.inspect(List.of("default"), from, to, ZoneId.of("Asia/Shanghai"));
        assertTrue(account(shanghai).fields().get("基线状态").startsWith("可参考；历史可定位认证日期 2 天"));
        var utc = service.inspect(List.of("default"), from, to, ZoneId.of("UTC"));
        assertTrue(account(utc).fields().get("基线状态").startsWith("基线不足；历史可定位认证日期 1 天"));
    }

    private static InspectionSection.Item account(InspectionSection section) {
        return section.items().stream().filter(item -> item.title().startsWith("账号 ")).findFirst().orElseThrow();
    }

    @Test
    void insufficientHistoryIsNotPresentedAsNoDriftAndPrivateUnknownAreSeparated() throws Exception {
        geo(ip -> location("中国", "江苏", "南京", "10.0.0.1".equals(ip) ? "PRIVATE" : "9.9.9.9".equals(ip) ? "DB_UNAVAILABLE" : "RESOLVED"));
        db.ingest("default",
                successAuth(from.plusSeconds(1), "1.1.1.1", 1L, "alice"),
                successAuth(from.plusSeconds(2), "10.0.0.1", 1L, "alice"),
                successAuth(from.plusSeconds(3), "10.0.0.1", 1L, "alice"),
                successAuth(from.plusSeconds(4), "9.9.9.9", 1L, "alice"),
                successAuth(from.plusSeconds(5), "9.9.9.9", 1L, "alice"),
                successAuth(from.plusSeconds(6), "9.9.9.9", 1L, "alice"));
        var report = service.inspect(List.of("default"), from, to, ZoneId.of("UTC"));
        assertEquals("6", report.items().get(0).fields().get("成功认证次数"));
        assertEquals("2", report.items().get(0).fields().get("内网来源次数"));
        assertEquals("3", report.items().get(0).fields().get("位置未知次数"));
        var changes = report.items().stream().map(item -> item.fields().getOrDefault("位置变化", "")).toList();
        assertTrue(changes.contains("基线不足，暂不判断偏移"));
        assertTrue(changes.contains("位置未知，无法判断偏移"));
        assertTrue(changes.stream().noneMatch(change -> change.equals("未观察到新增位置")));
    }

    @Test
    void noSuccessfulAuthenticationIsReportedAsUnknownRatherThanUnchanged() {
        var report = service.inspect(List.of("default"), from, to, ZoneId.of("UTC"));
        assertEquals("0", report.items().get(0).fields().get("成功认证次数"));
        assertTrue(report.notes().stream().anyMatch(note -> note.contains("本窗口没有成功认证记录")));
        assertTrue(service.inspect(List.of(), from, to, ZoneId.of("UTC")).notes().get(0).contains("没有启用的网关"));
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
    void missingRegionDoesNotCountAsAnAdditionalRegionOrAnAccountRename() throws Exception {
        properties.setAuthMinimumHistoryDays(1);
        geo(ip -> location("中国", "1.1.1.1".equals(ip) ? "" : "江苏", "", "RESOLVED"));
        db.ingest("default",
                successAuth(from.plusSeconds(1), "1.1.1.1", 1L, "new-name"),
                successAuth(from.plusSeconds(2), "2.2.2.2", 1L, "new-name"),
                successAuth(from.minusSeconds(86400), "3.3.3.3", 1L, "old-name"));
        var report = service.inspect(List.of("default"), from, to, ZoneId.of("UTC"));
        var account = account(report);
        assertTrue(account.title().contains("new-name"));
        assertEquals("未观察到多个已定位国家 / 地区", account.fields().get("本窗口跨地区情况"));
    }
}
