package org.congcong.controlmanager.service.mail.inspection;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.congcong.controlmanager.entity.disk.DiskDetail;
import org.congcong.controlmanager.entity.disk.DiskMonitorSampleEntity;
import org.congcong.controlmanager.repository.disk.DiskMonitorHostRepository;
import org.congcong.controlmanager.repository.disk.DiskMonitorSampleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;

import static org.congcong.controlmanager.service.mail.inspection.InspectionFormats.*;

@Service
@RequiredArgsConstructor
public class DiskInspectionService {
    private final DiskMonitorHostRepository hosts;
    private final DiskMonitorSampleRepository samples;
    private final ObjectMapper mapper;
    private final InspectionProperties properties;

    @Transactional(readOnly = true)
    public InspectionSection inspect(Instant from, Instant to, ZoneId zone) {
        var items = new ArrayList<InspectionSection.Item>();
        var notes = new ArrayList<String>(List.of(
                "温度最高值为有效采样最高值；未提供的 SMART 属性不填零。",
                "增量基于边界邻近累计计数，缺少边界、换盘、计数重置或采样长时间中断时注明不完整。",
                "读写量为 SMART 计数推算，HDD Total_LBAs 按 512 B 换算；不代表实时磁盘吞吐。"));
        var hostRows = hosts.findAllByOrderByLastSeenAtDesc();
        long interval = properties.getDiskSampleMinutes() * 60L;
        long expected = Duration.between(from, to).getSeconds() / interval;
        for (var host : hostRows) {
            items.add(InspectionSection.item("采集主机 " + text(host.getHostName()) + "（" + host.getHostId() + "）",
                    "最后采样", time(host.getLastSeenAt(), zone),
                    "采集状态", freshness(host.getLastSeenAt(), to, interval)));
        }
        // Duplicate pushes with the same timestamp must not produce duplicate disk entries.
        Map<List<String>, DiskMonitorSampleEntity> latest = new LinkedHashMap<>();
        for (var sample : samples.findLatestSamples()) {
            latest.merge(List.of(sample.getHostId(), sample.getDevice()), sample,
                    (a, b) -> a.getId() != null && b.getId() != null && a.getId() > b.getId() ? a : b);
        }
        for (var entry : latest.values()) {
            try {
                items.add(disk(entry, from, to, zone, interval, expected));
            } catch (Exception error) {
                items.add(InspectionSection.item(entry.getHostId() + "/" + entry.getDevice(),
                        "状态", "样本不可用：解析或查询失败，无法判断磁盘状态。"));
            }
        }
        if (latest.isEmpty()) notes.add("没有磁盘样本，无法判断磁盘健康；请检查宿主机 SMART 上报。 ");
        for (var host : hostRows) {
            if (latest.keySet().stream().noneMatch(key -> key.get(0).equals(host.getHostId()))) {
                notes.add("主机 " + host.getHostId() + " 没有保留的磁盘样本。");
            }
        }
        return new InspectionSection("磁盘与采样巡检", items, notes);
    }

    private InspectionSection.Item disk(DiskMonitorSampleEntity latest, Instant from, Instant to, ZoneId zone,
                                        long interval, long expected) throws Exception {
        DiskDetail detail = mapper.readValue(latest.getDetailJson(), DiskDetail.class);
        InspectionSmartSnapshot current = InspectionSmartSnapshot.parse(latest.getRawSmartOutput(), detail.diskType());
        var item = InspectionSection.item(latest.getHostId() + "/" + latest.getDevice(),
                "型号 / 类型", text(detail.model()) + " / " + text(detail.diskType()),
                "序列号", text(current.serial()), "SMART 健康", text(current.health()),
                "最后采样", time(latest.getSampledAt(), zone),
                "采样状态", freshness(latest.getSampledAt(), to, interval),
                "当前温度", temperature(current.temperature()));
        if (latest.getSampledAt().isAfter(to.plusSeconds(60))) {
            item.fields().put("区间统计", "样本时间在未来，无法统计");
            return item;
        }
        var history = samples.findInspectionSamples(latest.getHostId(), latest.getDevice(), from, to);
        var snapshots = new ArrayList<TimedSnapshot>();
        var baseline = samples.findTopByHostIdAndDeviceAndSampledAtLessThanEqualOrderBySampledAtDesc(
                latest.getHostId(), latest.getDevice(), from);
        baseline.ifPresent(row -> snapshots.add(new TimedSnapshot(row.getSampledAt(),
                InspectionSmartSnapshot.parse(row.getRawSmartOutput(), detail.diskType()))));
        Set<Long> slots = new HashSet<>(), hotSlots = new HashSet<>();
        Integer maximum = null;
        int validTemperatures = 0;
        int threshold = "HDD".equals(detail.diskType()) ? properties.getHddTemperatureThreshold()
                : properties.getSsdTemperatureThreshold();
        for (var row : history) {
            var snapshot = InspectionSmartSnapshot.parse(row.getRawSmartOutput(), detail.diskType());
            // History for another physical disk on the same device path must not enter this disk's statistics.
            if (!sameDisk(current, snapshot)) continue;
            long slot = Duration.between(from, row.getSampledAt()).getSeconds() / interval;
            slots.add(slot);
            if (snapshot.temperature() != null) {
                maximum = maximum == null ? snapshot.temperature() : Math.max(maximum, snapshot.temperature());
                validTemperatures++;
                if (snapshot.temperature() >= threshold) hotSlots.add(slot);
            }
            snapshots.add(new TimedSnapshot(row.getSampledAt(), snapshot));
        }
        item.fields().put("区间采样覆盖", slots.size() + " / " + expected + " 个采样时段（期望间隔 "
                + properties.getDiskSampleMinutes() + " 分钟）");
        if (current.serial() == null || current.serial().isBlank()) {
            item.fields().put("磁盘身份", "缺少序列号，无法确认历史样本属于同一块物理磁盘");
        }
        item.fields().put("区间最高温度", temperature(maximum) + "（有效温度样本 " + validTemperatures + "）");
        item.fields().put("超温采样时段", hotSlots.size() + "（参考阈值 ≥ " + threshold + " ℃）");
        item.fields().put("区间读取量", delta(snapshots, InspectionSmartSnapshot::readBytes, from, to, interval).format(true));
        item.fields().put("区间写入量", delta(snapshots, InspectionSmartSnapshot::writtenBytes, from, to, interval).format(true));
        if (!"HDD".equals(detail.diskType())) {
            item.fields().put("寿命已使用 / 剩余", current.lifeUsed() == null ? "未提供"
                    : current.lifeUsed() + "% / " + Math.max(0, 100 - current.lifeUsed()) + "%");
        }
        if ("NVME_SSD".equals(detail.diskType())) {
            item.fields().put("NVMe Critical Warning", current.criticalWarning() == null ? "未提供"
                    : "0x" + current.criticalWarning().toString(16));
        }
        for (var error : current.errors().entrySet()) {
            var change = delta(snapshots, snapshot -> snapshot.errors().get(error.getKey()), from, to, interval);
            item.fields().put(error.getKey(), error.getValue() == null ? "未提供"
                    : "累计 " + error.getValue() + "；区间新增 " + change.format(false));
        }
        return item;
    }

    private static boolean sameDisk(InspectionSmartSnapshot a, InspectionSmartSnapshot b) {
        return a.serial() != null && !a.serial().isBlank() && a.serial().equals(b.serial());
    }

    private static String temperature(Integer value) {
        return value == null ? "未提供" : value + " ℃";
    }

    private static String freshness(Instant sample, Instant to, long interval) {
        if (sample == null) return "未采集";
        if (sample.isAfter(to.plusSeconds(60))) return "采样时间在未来";
        return sample.isBefore(to.minusSeconds(interval * 3)) ? "缺报 / 样本过期" : "最近有采样";
    }

    private record TimedSnapshot(Instant at, InspectionSmartSnapshot snapshot) { }
    private record Delta(BigInteger value, boolean partial) {
        String format(boolean isBytes) {
            if (value == null) return "未提供或缺少可比较样本";
            return (partial ? "至少 " : "") + (isBytes ? bytes(value) : value.toString())
                    + (partial ? "（边界缺失、换盘、重置或采样中断，统计不完整）" : "");
        }
    }

    private static Delta delta(List<TimedSnapshot> rows, Function<InspectionSmartSnapshot, BigInteger> counter,
                               Instant from, Instant to, long interval) {
        BigInteger value = BigInteger.ZERO;
        int pairs = 0;
        boolean partial = rows.isEmpty() || rows.get(0).at().isAfter(from)
                || rows.get(0).at().isBefore(from.minusSeconds(interval * 3))
                || rows.get(rows.size() - 1).at().isBefore(to.minusSeconds(interval * 3));
        for (int i = 1; i < rows.size(); i++) {
            var previous = rows.get(i - 1);
            var current = rows.get(i);
            // A counter interval crossing the start may contain activity outside the report window.
            // Keep the boundary sample for identity checks, but do not count that interval.
            if (previous.at().isBefore(from)) {
                if (current.at().isAfter(from)) partial = true;
                continue;
            }
            BigInteger a = counter.apply(previous.snapshot()), b = counter.apply(current.snapshot());
            if (!sameDisk(previous.snapshot(), current.snapshot()) || a == null || b == null || b.compareTo(a) < 0) {
                partial = true;
                continue;
            }
            if (Duration.between(previous.at(), current.at()).getSeconds() > interval * 3) partial = true;
            value = value.add(b.subtract(a));
            pairs++;
        }
        return new Delta(pairs == 0 ? null : value, partial);
    }
}
