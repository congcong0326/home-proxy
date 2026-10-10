package org.congcong.controlmanager.service.mail.inspection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.congcong.controlmanager.entity.disk.DiskMonitorSampleEntity;
import org.congcong.controlmanager.repository.disk.DiskMonitorHostRepository;
import org.congcong.controlmanager.repository.disk.DiskMonitorSampleRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiskInspectionServiceTest {
    private final DiskMonitorHostRepository hosts = mock(DiskMonitorHostRepository.class);
    private final DiskMonitorSampleRepository samples = mock(DiskMonitorSampleRepository.class);
    private final DiskInspectionService service = new DiskInspectionService(hosts, samples, new ObjectMapper(), new InspectionProperties());
    private final Instant from = Instant.parse("2026-10-09T01:00:00Z"), to = Instant.parse("2026-10-10T01:00:00Z");

    private DiskMonitorSampleEntity sample(Instant at, String serial, long write, int temperature, int errors) {
        var row = new DiskMonitorSampleEntity(); row.setHostId("nas"); row.setDevice("nvme0n1"); row.setSampledAt(at);
        row.setDetailJson("{\"diskType\":\"NVME_SSD\",\"model\":\"Test SSD\"}");
        row.setRawSmartOutput("Serial Number: " + serial + "\nSMART overall-health self-assessment test result: PASSED\n"
                + "Temperature: " + temperature + " Celsius\nPercentage Used: 0%\nCritical Warning: 0x00\n"
                + "Data Units Written: " + write + "\nMedia and Data Integrity Errors: " + errors + "\n");
        return row;
    }

    @Test
    void usesTheActualWindowAndBoundarySampleForDailyDeltas() {
        var history = new ArrayList<DiskMonitorSampleEntity>();
        for (int i = 0; i < 144; i++) history.add(sample(from.plusSeconds(i * 600L), "SN", 100 + i, i == 50 ? 75 : 35, i < 50 ? 0 : 3));
        var latest = history.get(history.size() - 1);
        when(samples.findLatestSamples()).thenReturn(List.of(latest));
        when(samples.findInspectionSamples("nas", "nvme0n1", from, to)).thenReturn(history);
        when(samples.findTopByHostIdAndDeviceAndSampledAtLessThanEqualOrderBySampledAtDesc("nas", "nvme0n1", from))
                .thenReturn(Optional.of(sample(from.minusSeconds(600), "SN", 99, 35, 0)));
        var fields = service.inspect(from, to, ZoneId.of("Asia/Shanghai")).items().get(0).fields();
        assertTrue(fields.get("区间采样覆盖").startsWith("144 / 144"));
        assertTrue(fields.get("区间写入量").contains("73216000 B"));
        assertFalse(fields.get("区间写入量").contains("不完整"));
        assertTrue(fields.get("区间最高温度").startsWith("75 ℃"));
        assertTrue(fields.get("超温采样时段").startsWith("1"));
        assertEquals("累计 3；区间新增 3", fields.get("媒体与数据完整性错误"));
        assertEquals("未提供", fields.get("异常断电次数"));
        verify(samples).findInspectionSamples("nas", "nvme0n1", from, to);
    }

    @Test
    void doesNotCountActivityBeforeTheWindowAsAnInspectionLowerBound() {
        var boundary = sample(from.minusSeconds(600), "SN", 10, 35, 0);
        var first = sample(from.plusSeconds(600), "SN", 100, 35, 0);
        var last = sample(to.minusSeconds(600), "SN", 110, 35, 0);
        when(samples.findLatestSamples()).thenReturn(List.of(last));
        when(samples.findInspectionSamples("nas", "nvme0n1", from, to)).thenReturn(List.of(first, last));
        when(samples.findTopByHostIdAndDeviceAndSampledAtLessThanEqualOrderBySampledAtDesc("nas", "nvme0n1", from))
                .thenReturn(Optional.of(boundary));
        var fields = service.inspect(from, to, ZoneId.of("UTC")).items().get(0).fields();
        assertTrue(fields.get("区间写入量").contains("5120000 B"));
        assertTrue(fields.get("区间写入量").startsWith("至少 "));
        assertTrue(fields.get("区间写入量").contains("统计不完整"));
    }

    @Test
    void doesNotSubtractDifferentDisksOrHideCounterResetAsZero() {
        var a = sample(from, "OLD", 100000, 90, 50);
        var b = sample(from.plusSeconds(600), "NEW", 100, 35, 0);
        var c = sample(from.plusSeconds(1200), "NEW", 10, 36, 0);
        var d = sample(from.plusSeconds(1800), "NEW", 20, 37, 0);
        when(samples.findLatestSamples()).thenReturn(List.of(d));
        when(samples.findInspectionSamples("nas", "nvme0n1", from, to)).thenReturn(List.of(a, b, c, d));
        when(samples.findTopByHostIdAndDeviceAndSampledAtLessThanEqualOrderBySampledAtDesc("nas", "nvme0n1", from))
                .thenReturn(Optional.of(a));
        var fields = service.inspect(from, to, ZoneId.of("UTC")).items().get(0).fields();
        assertTrue(fields.get("区间写入量").contains("5120000 B"));
        assertTrue(fields.get("区间写入量").contains("统计不完整"));
        assertTrue(fields.get("区间最高温度").startsWith("37 ℃"));
        assertTrue(fields.get("采样状态").contains("过期"));
    }

    @Test
    void emptySamplesAreUnknownRatherThanHealthy() {
        var report = service.inspect(from, to, ZoneId.of("UTC"));
        assertTrue(report.items().isEmpty());
        assertTrue(report.notes().stream().anyMatch(note -> note.contains("无法判断磁盘健康")));
    }

    @Test
    void usesMeasuredHddTemperaturesForCurrentMaximumAndHotSlotsDespiteDuplicateNames() {
        var history = new ArrayList<DiskMonitorSampleEntity>();
        for (int i = 0; i < 144; i++) {
            var row = new DiskMonitorSampleEntity();
            row.setHostId("nas"); row.setDevice("sda"); row.setSampledAt(from.plusSeconds(i * 600L));
            row.setDetailJson("{\"diskType\":\"HDD\",\"model\":\"Test HDD\"}");
            row.setRawSmartOutput("Serial Number: HDD-SN\nSMART overall-health self-assessment test result: PASSED\n"
                    + "194 Temperature_Celsius 0x0002 171 171 000 Old_age Always - " + (i == 50 ? 56 : 35)
                    + " (Min/Max 23/56)\n231 Temperature_Celsius 0x0032 100 100 000 Old_age Always - 0\n");
            history.add(row);
        }
        when(samples.findLatestSamples()).thenReturn(List.of(history.get(143)));
        when(samples.findInspectionSamples("nas", "sda", from, to)).thenReturn(history);
        when(samples.findTopByHostIdAndDeviceAndSampledAtLessThanEqualOrderBySampledAtDesc("nas", "sda", from))
                .thenReturn(Optional.of(history.get(0)));
        var fields = service.inspect(from, to, ZoneId.of("UTC")).items().get(0).fields();
        assertEquals("35 ℃", fields.get("当前温度"));
        assertEquals("56 ℃（有效温度样本 144）", fields.get("区间最高温度"));
        assertEquals("1（参考阈值 ≥ 50 ℃）", fields.get("超温采样时段"));
        assertTrue(fields.get("区间采样覆盖").startsWith("144 / 144"));
    }
}
