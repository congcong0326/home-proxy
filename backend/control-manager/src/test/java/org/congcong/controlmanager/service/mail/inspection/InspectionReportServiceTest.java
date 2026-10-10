package org.congcong.controlmanager.service.mail.inspection;

import org.congcong.controlmanager.entity.ProxyWorker;
import org.congcong.controlmanager.repository.ProxyWorkerRepository;
import org.congcong.controlmanager.repository.WorkerStatusRepository;
import org.congcong.controlmanager.telemetry.TelemetryStore;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InspectionReportServiceTest {
    @Test
    void usesRolling24HoursAcrossDstAndKeepsOtherSectionsWhenAQueryFails() {
        var disks = mock(DiskInspectionService.class);
        var network = mock(NetworkInspectionService.class);
        var auth = mock(AuthLocationInspectionService.class);
        var workers = mock(ProxyWorkerRepository.class);
        var statuses = mock(WorkerStatusRepository.class);
        var telemetry = mock(TelemetryStore.class);
        var properties = new InspectionProperties(); properties.setTimeZone("Europe/Berlin");
        var enabled = new ProxyWorker(); enabled.setWorkerId("default"); enabled.setEnabled(true);
        var disabled = new ProxyWorker(); disabled.setWorkerId("disabled"); disabled.setEnabled(false);
        when(workers.findAll()).thenReturn(List.of(enabled, disabled));
        Instant to = Instant.parse("2026-10-25T09:00:00Z");
        Instant from = to.minus(Duration.ofHours(24));
        var zone = ZoneId.of("Europe/Berlin");
        when(disks.inspect(from, to, zone)).thenReturn(new InspectionSection("磁盘", List.of(), List.of("有效")));
        when(network.traffic(List.of("default"), from, to, zone)).thenThrow(new IllegalStateException("CK_UNAVAILABLE"));
        when(network.connections(List.of("default"), from, to, zone)).thenReturn(new InspectionSection("连接", List.of(), List.of("有效")));
        when(network.dns(List.of("default"), from, to, zone)).thenReturn(new InspectionSection("DNS", List.of(), List.of("有效")));
        when(auth.inspect(List.of("default"), from, to, zone)).thenReturn(new InspectionSection("认证", List.of(), List.of("有效")));
        var service = new InspectionReportService(disks, network, auth, workers, statuses, telemetry, properties, Clock.fixed(to, zone));
        var report = service.build();
        assertEquals(Duration.ofHours(24), Duration.between(report.from(), report.to()));
        assertEquals(7, report.sections().size());
        assertTrue(report.sections().get(2).notes().get(0).contains("无法判断"));
        assertEquals("认证", report.sections().get(5).title());
        verify(network).connections(List.of("default"), from, to, zone);
    }

    @Test
    void escapesHtmlAndProducesTheSameFactsInPlainText() {
        var fields = InspectionSection.item("<script>bad</script>", "来源", "<img src='https://bad'>", "字节", "18446744073709551620 B");
        var section = new InspectionSection("认证", List.of(fields), List.of("位置未知，无法判断"));
        Instant to = Instant.parse("2026-10-10T01:00:00Z");
        var report = new InspectionReport("id", to.minus(Duration.ofHours(24)), to, ZoneId.of("Asia/Shanghai"), List.of(section));
        var renderer = new InspectionMailRenderer();
        String html = renderer.html(report), plain = renderer.plainText(report);
        assertFalse(html.contains("<script>")); assertFalse(html.contains("<img src="));
        assertTrue(html.contains("&lt;script&gt;")); assertTrue(html.contains("18446744073709551620 B"));
        assertTrue(plain.contains("18446744073709551620 B")); assertTrue(plain.contains("位置未知，无法判断"));
        assertTrue(html.contains("2026-10-10T09:00:00+08:00"));
        assertTrue(plain.contains("起含止不含"));
    }
}
