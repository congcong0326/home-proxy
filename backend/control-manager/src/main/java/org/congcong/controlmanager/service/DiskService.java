package org.congcong.controlmanager.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.congcong.controlmanager.config.DiskMonitorProperties;
import org.congcong.controlmanager.dto.disk.DiskPushRequest;
import org.congcong.controlmanager.entity.disk.DiskDetail;
import org.congcong.controlmanager.entity.disk.DiskHost;
import org.congcong.controlmanager.entity.disk.DiskInfo;
import org.congcong.controlmanager.entity.disk.DiskIoStats;
import org.congcong.controlmanager.entity.disk.DiskMonitorHostEntity;
import org.congcong.controlmanager.entity.disk.DiskMonitorSampleEntity;
import org.congcong.controlmanager.repository.disk.DiskMonitorHostRepository;
import org.congcong.controlmanager.repository.disk.DiskMonitorSampleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class DiskService {

    private static final int TEN_MINUTE_SLOTS_PER_DAY = 144;

    private final DiskMonitorHostRepository hostRepository;
    private final DiskMonitorSampleRepository sampleRepository;
    private final ObjectMapper objectMapper;
    private final DiskMonitorProperties properties;
    private final Clock clock;

    @Autowired
    public DiskService(DiskMonitorHostRepository hostRepository,
                       DiskMonitorSampleRepository sampleRepository,
                       ObjectMapper objectMapper,
                       DiskMonitorProperties properties) {
        this(hostRepository, sampleRepository, objectMapper, properties, Clock.systemDefaultZone());
    }

    public DiskService(DiskMonitorHostRepository hostRepository,
                       DiskMonitorSampleRepository sampleRepository,
                       ObjectMapper objectMapper,
                       DiskMonitorProperties properties,
                       Clock clock) {
        this.hostRepository = hostRepository;
        this.sampleRepository = sampleRepository;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public void acceptPush(DiskPushRequest request, String sourceIp) {
        Instant sampledAt = request.getSampledAt() == null ? Instant.now(clock) : request.getSampledAt();
        String hostId = normalizeRequired(request.getHostId(), "hostId");
        String hostName = normalizeHostName(request.getHostName(), hostId);

        DiskMonitorHostEntity host = hostRepository.findByHostId(hostId).orElseGet(DiskMonitorHostEntity::new);
        host.setHostId(hostId);
        host.setHostName(hostName);
        host.setLastSeenAt(sampledAt);
        host.setLastSourceIp(sourceIp);
        hostRepository.save(host);

        for (DiskPushRequest.DiskSample disk : request.getDisks()) {
            String device = normalizeDevice(disk.getDevice());
            DiskDetail detail = SmartCtlParser.parseDetail(device, disk.getSmartctlOutput(),
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

            DiskMonitorSampleEntity sample = new DiskMonitorSampleEntity();
            sample.setHostId(hostId);
            sample.setDevice(device);
            sample.setSampledAt(sampledAt);
            sample.setRawSmartOutput(disk.getSmartctlOutput());
            sample.setDetailJson(writeDetail(detail));
            sampleRepository.save(sample);
        }
    }

    @Transactional(readOnly = true)
    public List<DiskHost> getHosts() {
        return hostRepository.findAllByOrderByLastSeenAtDesc().stream()
                .map(host -> new DiskHost(host.getHostId(), host.getHostName(), host.getLastSeenAt(), host.getLastSourceIp()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DiskInfo> getAllDisks() {
        return sampleRepository.findLatestSamples().stream()
                .map(this::toDiskInfo)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DiskInfo> getAllDisks(String hostId) {
        if (hostId == null || hostId.isBlank()) {
            return getAllDisks();
        }
        return sampleRepository.findLatestSamplesByHostId(hostId.trim()).stream()
                .map(this::toDiskInfo)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DiskIoStats> getDailyIoStats() {
        return sampleRepository.findLatestSamples().stream()
                .map(sample -> buildDiskIoStats(sample.getHostId(), sample.getDevice()))
                .toList();
    }

    @Transactional(readOnly = true)
    public DiskDetail getDiskDetail(String device) {
        String normalizedDevice = normalizeDevice(device);
        DiskMonitorSampleEntity latest = sampleRepository.findTopByDeviceOrderBySampledAtDesc(normalizedDevice)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
        return buildDiskDetail(latest.getHostId(), normalizedDevice);
    }

    @Transactional(readOnly = true)
    public DiskDetail getDiskDetail(String hostId, String device) {
        if (hostId == null || hostId.isBlank()) {
            return getDiskDetail(device);
        }
        return buildDiskDetail(hostId.trim(), normalizeDevice(device));
    }

    @Scheduled(cron = "0 30 2 * * ?")
    @Transactional
    public void cleanupExpiredSamples() {
        int retentionDays = Math.max(1, properties.getRetentionDays());
        Instant threshold = Instant.now(clock).minus(Duration.ofDays(retentionDays));
        sampleRepository.deleteBySampledAtBefore(threshold);
    }

    private DiskDetail buildDiskDetail(String hostId, String device) {
        DiskMonitorSampleEntity latest = sampleRepository.findTopByHostIdAndDeviceOrderBySampledAtDesc(hostId, device)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
        DiskDetail detail = readDetail(latest);
        History history = buildTodayHistory(hostId, device);
        return copyWithHistory(detail, history.temperatures(), history.readDeltas(), history.writeDeltas());
    }

    private DiskIoStats buildDiskIoStats(String hostId, String device) {
        History history = buildTodayHistory(hostId, device);
        long totalRead = history.readDeltas().stream().mapToLong(Long::longValue).sum();
        long totalWrite = history.writeDeltas().stream().mapToLong(Long::longValue).sum();
        return new DiskIoStats(hostId + "/" + device,
                history.readDeltas(), history.writeDeltas(), totalRead, totalWrite);
    }

    private History buildTodayHistory(String hostId, String device) {
        ZoneId zone = clock.getZone();
        LocalDate today = LocalDate.now(clock);
        Instant start = today.atStartOfDay(zone).toInstant();
        Instant end = today.plusDays(1).atStartOfDay(zone).toInstant();

        List<Integer> temperatures = new ArrayList<>();
        List<Long> readDeltas = new ArrayList<>();
        List<Long> writeDeltas = new ArrayList<>();
        Long previousRead = null;
        Long previousWrite = null;

        for (DiskMonitorSampleEntity sample : sampleRepository
                .findByHostIdAndDeviceAndSampledAtBetweenOrderBySampledAtAsc(hostId, device, start, end)) {
            DiskDetail detail = readDetail(sample);
            int slot = slotOf(sample.getSampledAt(), zone);
            if (slot < 0 || slot >= TEN_MINUTE_SLOTS_PER_DAY) {
                continue;
            }

            fillToSlot(temperatures, slot, 0);
            fillToSlot(readDeltas, slot, 0L);
            fillToSlot(writeDeltas, slot, 0L);

            temperatures.set(slot, detail.temperature());
            readDeltas.set(slot, previousRead == null ? 0L : Math.max(0L, detail.dataUnitsRead() - previousRead));
            writeDeltas.set(slot, previousWrite == null ? 0L : Math.max(0L, detail.dataUnitsWritten() - previousWrite));
            previousRead = detail.dataUnitsRead();
            previousWrite = detail.dataUnitsWritten();
        }

        return new History(temperatures, readDeltas, writeDeltas);
    }

    private int slotOf(Instant instant, ZoneId zone) {
        LocalTime time = ZonedDateTime.ofInstant(instant, zone).toLocalTime();
        return time.getHour() * 6 + time.getMinute() / 10;
    }

    private DiskInfo toDiskInfo(DiskMonitorSampleEntity sample) {
        DiskDetail detail = readDetail(sample);
        return new DiskInfo(detail.device(), detail.model(), detail.serial(), detail.size(), detail.health(), detail.temperature());
    }

    private DiskDetail readDetail(DiskMonitorSampleEntity sample) {
        try {
            return objectMapper.readValue(sample.getDetailJson(), DiskDetail.class);
        } catch (JsonProcessingException e) {
            log.error("解析磁盘详情JSON失败 hostId={} device={}", sample.getHostId(), sample.getDevice(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid disk sample detail");
        }
    }

    private String writeDetail(DiskDetail detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid disk sample detail");
        }
    }

    private DiskDetail copyWithHistory(DiskDetail detail, List<Integer> temperatures,
                                       List<Long> readDeltas, List<Long> writeDeltas) {
        return new DiskDetail(
                detail.device(), detail.model(), detail.serial(), detail.size(), detail.temperature(), detail.health(),
                detail.smartSupported(), detail.smartEnabled(), detail.powerOnHours(), detail.powerCycleCount(),
                detail.dataUnitsRead(), detail.dataUnitsWritten(), detail.reallocatedSectorCount(),
                detail.seekErrorRate(), detail.spinRetryCount(), detail.udmaCrcErrorCount(), detail.percentageUsed(),
                detail.unsafeShutdowns(), detail.mediaErrors(), detail.ssdLifeLeft(), detail.flashWritesGiB(),
                detail.lifetimeWritesGiB(), detail.lifetimeReadsGiB(), detail.averageEraseCount(),
                detail.maxEraseCount(), detail.totalEraseCount(), detail.diskType(),
                temperatures, readDeltas, writeDeltas);
    }

    private String normalizeDevice(String device) {
        String normalized = normalizeRequired(device, "device");
        if (normalized.startsWith("/dev/")) {
            return normalized.substring("/dev/".length());
        }
        if (normalized.startsWith("dev/")) {
            return normalized.substring("dev/".length());
        }
        return normalized;
    }

    private String normalizeHostName(String hostName, String hostId) {
        if (hostName == null || hostName.isBlank()) {
            return hostId;
        }
        return hostName.trim();
    }

    private String normalizeRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
        }
        return value.trim();
    }

    private void fillToSlot(List<Integer> list, int slot, Integer defaultVal) {
        while (list.size() <= slot) {
            list.add(defaultVal);
        }
    }

    private void fillToSlot(List<Long> list, int slot, Long defaultVal) {
        while (list.size() <= slot) {
            list.add(defaultVal);
        }
    }

    private record History(List<Integer> temperatures, List<Long> readDeltas, List<Long> writeDeltas) {
    }
}
