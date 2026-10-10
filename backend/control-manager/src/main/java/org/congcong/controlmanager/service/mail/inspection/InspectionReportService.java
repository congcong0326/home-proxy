package org.congcong.controlmanager.service.mail.inspection;

import lombok.extern.slf4j.Slf4j;
import org.congcong.controlmanager.entity.ProxyWorker;
import org.congcong.controlmanager.entity.WorkerStatus;
import org.congcong.controlmanager.repository.ProxyWorkerRepository;
import org.congcong.controlmanager.repository.WorkerStatusRepository;
import org.congcong.controlmanager.telemetry.TelemetryStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;

import static org.congcong.controlmanager.service.mail.inspection.InspectionFormats.*;

@Slf4j
@Service
public class InspectionReportService {
    private final DiskInspectionService disks;
    private final NetworkInspectionService network;
    private final AuthLocationInspectionService auth;
    private final ProxyWorkerRepository workers;
    private final WorkerStatusRepository statuses;
    private final TelemetryStore telemetry;
    private final InspectionProperties properties;
    private final Clock clock;

    @Autowired
    public InspectionReportService(DiskInspectionService disks, NetworkInspectionService network,
            AuthLocationInspectionService auth, ProxyWorkerRepository workers, WorkerStatusRepository statuses,
            TelemetryStore telemetry, InspectionProperties properties) {
        this(disks, network, auth, workers, statuses, telemetry, properties, Clock.systemDefaultZone());
    }

    InspectionReportService(DiskInspectionService disks, NetworkInspectionService network,
            AuthLocationInspectionService auth, ProxyWorkerRepository workers, WorkerStatusRepository statuses,
            TelemetryStore telemetry, InspectionProperties properties, Clock clock) {
        this.disks = disks; this.network = network; this.auth = auth; this.workers = workers;
        this.statuses = statuses; this.telemetry = telemetry; this.properties = properties; this.clock = clock;
    }

    public InspectionReport build() {
        Instant to = clock.instant(), from = to.minus(24, ChronoUnit.HOURS);
        ZoneId zone = ZoneId.of(properties.getTimeZone());
        List<ProxyWorker> enabled = enabledWorkers();
        List<String> ids = enabled == null ? null : enabled.stream().map(ProxyWorker::getWorkerId).toList();
        var sections = List.of(
                safe("磁盘与采样巡检", () -> disks.inspect(from, to, zone)),
                safe("网关最新运行状态", () -> gateways(required(enabled), to, zone)),
                safe("外网上下行流量", () -> network.traffic(required(ids), from, to, zone)),
                safe("代理连接质量", () -> network.connections(required(ids), from, to, zone)),
                safe("客户端 DNS 处理质量", () -> network.dns(required(ids), from, to, zone)),
                safe("认证成功位置与变化", () -> auth.inspect(required(ids), from, to, zone)),
                safe("日志采集与数据覆盖", () -> collection(required(enabled), zone)));
        return new InspectionReport(UUID.randomUUID().toString(), from, to, zone, sections);
    }

    private List<ProxyWorker> enabledWorkers() {
        try {
            return workers.findAll().stream().filter(worker -> Boolean.TRUE.equals(worker.getEnabled()))
                    .sorted(Comparator.comparing(ProxyWorker::getWorkerId)).toList();
        } catch (RuntimeException error) {
            log.warn("巡检无法读取网关范围", error);
            return null;
        }
    }

    private InspectionSection gateways(List<ProxyWorker> enabled, Instant to, ZoneId zone) {
        Map<String, WorkerStatus> current = new HashMap<>();
        for (var status : statuses.findAll()) current.put(status.getWorkerId(), status);
        var items = new ArrayList<InspectionSection.Item>();
        for (var worker : enabled) {
            var status = current.get(worker.getWorkerId());
            Instant seen = status == null || status.getLastSeenAt() == null ? null
                    : status.getLastSeenAt().atZone(clock.getZone()).toInstant();
            Instant started = status == null || status.getStartedAt() == null ? null
                    : status.getStartedAt().atZone(clock.getZone()).toInstant();
            String online = seen == null ? "未知（未收到心跳）" : seen.isAfter(to.plusSeconds(10))
                    ? "未知（心跳时间在未来）" : seen.isBefore(to.minusSeconds(15)) ? "离线 / 心跳过期" : "在线";
            items.add(InspectionSection.item(text(worker.getDisplayName()) + "（" + worker.getWorkerId() + "）",
                    "当前状态", online, "最近心跳", time(seen, zone), "最近启动", time(started, zone),
                    "配置应用", status == null || status.getApplySuccess() == null ? "未知"
                            : Boolean.TRUE.equals(status.getApplySuccess()) ? "成功" : "失败，请查看网关详情",
                    "运行入站数", text(status == null ? null : status.getRunningInboundCount()),
                    "日志采集配置", Boolean.FALSE.equals(worker.getLogReportingEnabled()) ? "关闭" : "启用"));
        }
        return new InspectionSection("网关最新运行状态", items, List.of(
                "只展示启用节点的最新状态；现有心跳存储不保留全天离线时长、重启次数或历次配置应用结果。"));
    }

    private InspectionSection collection(List<ProxyWorker> enabled, ZoneId zone) {
        var items = new ArrayList<InspectionSection.Item>();
        for (var worker : enabled) {
            var state = telemetry.workerStatus(worker.getWorkerId());
            items.add(InspectionSection.item("网关 " + worker.getWorkerId(),
                    "上报配置", Boolean.FALSE.equals(worker.getLogReportingEnabled()) ? "关闭" : "启用",
                    "实际采集开关", state.get("enabled") instanceof Boolean value ? (value ? "开启" : "关闭") : "未知",
                    "上报有效性", state.get("online") instanceof Boolean value ? (value ? "最近有上报" : "上报过期") : "未知",
                    "采集状态更新时间", time(instant(state.get("lastSeenAt")), zone),
                    "进程实例", text(state.get("instanceId")), "最近上报待确认记录", text(state.get("queueDepth")),
                    "本实例累计丢弃", text(state.get("dropped")), "本实例序列化失败", text(state.get("serializationFailures"))));
        }
        var buffer = telemetry.bufferStatus();
        items.add(InspectionSection.item("管理端待落库数据", "当前队列记录数", text(buffer.get("queueDepth")),
                "各类待写入记录", text(buffer.get("tables"))));
        return new InspectionSection("日志采集与数据覆盖", items, List.of(
                "采集计数属于当前进程，重启会重置；不能据当前零丢弃认定全天数据完整。",
                "Worker 和管理端使用内存缓冲，重启可能丢失未落库数据；ACK 表示管理端已接收，不代表已持久化。",
                "管理端正常攒批可能延迟约两分钟，故障时更久；巡检查询只统计已经落库的数据。",
                "没有代理活动时，现有日志无法证明宽带、外部网站或 NAS 服务可用；本次巡检不包含主动连通性探测。"));
    }

    private static <T> List<T> required(List<T> value) {
        if (value == null) throw new IllegalStateException("Worker scope unavailable");
        return value;
    }

    private static InspectionSection safe(String title, Supplier<InspectionSection> build) {
        try { return build.get(); }
        catch (RuntimeException error) {
            log.warn("巡检项生成失败 title={}", title, error);
            return InspectionSection.unavailable(title);
        }
    }
}
