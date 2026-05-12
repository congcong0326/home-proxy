package org.congcong.controlmanager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.congcong.controlmanager.config.DiskMonitorProperties;
import org.congcong.controlmanager.dto.disk.DiskPushRequest;
import org.congcong.controlmanager.entity.disk.DiskDetail;
import org.congcong.controlmanager.entity.disk.DiskInfo;
import org.congcong.controlmanager.entity.disk.DiskMonitorHostEntity;
import org.congcong.controlmanager.entity.disk.DiskMonitorSampleEntity;
import org.congcong.controlmanager.repository.disk.DiskMonitorHostRepository;
import org.congcong.controlmanager.repository.disk.DiskMonitorSampleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DiskService 主动推送模式")
class DiskServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-12T02:30:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));

    @Mock
    private DiskMonitorHostRepository hostRepository;

    @Mock
    private DiskMonitorSampleRepository sampleRepository;

    private DiskService diskService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        DiskMonitorProperties properties = new DiskMonitorProperties();
        properties.setRetentionDays(7);
        diskService = new DiskService(hostRepository, sampleRepository, objectMapper, properties, CLOCK);
    }

    @Test
    @DisplayName("接收宿主机推送后创建主机并保存解析后的磁盘样本")
    void acceptsPushAndStoresParsedSamples() throws Exception {
        when(hostRepository.findByHostId("nas-main")).thenReturn(Optional.empty());
        when(hostRepository.save(any(DiskMonitorHostEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sampleRepository.save(any(DiskMonitorSampleEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        diskService.acceptPush(pushRequest("nas-main", "NAS Main", Instant.parse("2026-05-12T02:20:00Z"),
                new DiskPushRequest.DiskSample("sda", smartctlOutput("Disk One", "SN001", 35, 1000, 2000))),
                "192.168.1.10");

        ArgumentCaptor<DiskMonitorHostEntity> hostCaptor = ArgumentCaptor.forClass(DiskMonitorHostEntity.class);
        verify(hostRepository).save(hostCaptor.capture());
        assertEquals("nas-main", hostCaptor.getValue().getHostId());
        assertEquals("NAS Main", hostCaptor.getValue().getHostName());
        assertEquals("192.168.1.10", hostCaptor.getValue().getLastSourceIp());
        assertEquals(Instant.parse("2026-05-12T02:20:00Z"), hostCaptor.getValue().getLastSeenAt());

        ArgumentCaptor<DiskMonitorSampleEntity> sampleCaptor = ArgumentCaptor.forClass(DiskMonitorSampleEntity.class);
        verify(sampleRepository).save(sampleCaptor.capture());
        DiskMonitorSampleEntity saved = sampleCaptor.getValue();
        assertEquals("nas-main", saved.getHostId());
        assertEquals("sda", saved.getDevice());
        assertTrue(saved.getRawSmartOutput().contains("Device Model:"));
        assertTrue(saved.getRawSmartOutput().contains("Disk One"));
        assertTrue(saved.getDetailJson().contains("\"model\":\"Disk One\""));
    }

    @Test
    @DisplayName("按主机查询最新磁盘列表")
    void listsLatestDisksForHost() throws Exception {
        when(sampleRepository.findLatestSamplesByHostId("nas-main")).thenReturn(List.of(
                sample("nas-main", "sda", Instant.parse("2026-05-12T02:20:00Z"), detail("sda", "Disk One", "SN001", 35, 1000, 2000)),
                sample("nas-main", "nvme0n1", Instant.parse("2026-05-12T02:20:00Z"), detail("nvme0n1", "Disk Two", "SN002", 41, 3000, 4000))
        ));

        List<DiskInfo> disks = diskService.getAllDisks("nas-main");

        assertEquals(2, disks.size());
        assertEquals("sda", disks.get(0).device());
        assertEquals("Disk One", disks.get(0).model());
        assertEquals("PASSED", disks.get(0).status());
        assertEquals(41, disks.get(1).temperature());
    }

    @Test
    @DisplayName("查询磁盘详情时组装当天10分钟粒度温度与读写增量")
    void detailIncludesTodayHistoryByTenMinuteSlots() throws Exception {
        DiskMonitorSampleEntity latest = sample("nas-main", "sda", Instant.parse("2026-05-12T00:10:00Z"),
                detail("sda", "Disk One", "SN001", 31, 1_500, 2_700));
        when(sampleRepository.findTopByHostIdAndDeviceOrderBySampledAtDesc("nas-main", "sda")).thenReturn(Optional.of(latest));
        when(sampleRepository.findByHostIdAndDeviceAndSampledAtBetweenOrderBySampledAtAsc(
                "nas-main", "sda", Instant.parse("2026-05-12T00:00:00Z"), Instant.parse("2026-05-13T00:00:00Z")))
                .thenReturn(List.of(
                        sample("nas-main", "sda", Instant.parse("2026-05-12T00:00:00Z"), detail("sda", "Disk One", "SN001", 30, 1_000, 2_000)),
                        latest
                ));

        DiskDetail detail = diskService.getDiskDetail("nas-main", "sda");

        assertEquals("Disk One", detail.model());
        assertEquals(31, detail.temperature());
        assertEquals(30, detail.historyTemperature().get(0));
        assertEquals(31, detail.historyTemperature().get(1));
        assertEquals(0L, detail.historyReadBytes().get(0));
        assertEquals(500L, detail.historyReadBytes().get(1));
        assertEquals(700L, detail.historyWriteBytes().get(1));
    }

    @Test
    @DisplayName("清理超过保留周期的历史样本")
    void cleanupRemovesExpiredSamples() {
        diskService.cleanupExpiredSamples();

        verify(sampleRepository).deleteBySampledAtBefore(Instant.parse("2026-05-05T02:30:00Z"));
    }

    @Test
    @DisplayName("未上报任何主机时主机列表为空")
    void listsHostsFromRepository() {
        when(hostRepository.findAllByOrderByLastSeenAtDesc()).thenReturn(List.of());

        assertTrue(diskService.getHosts().isEmpty());
    }

    private DiskPushRequest pushRequest(String hostId, String hostName, Instant sampledAt, DiskPushRequest.DiskSample... samples) {
        DiskPushRequest request = new DiskPushRequest();
        request.setHostId(hostId);
        request.setHostName(hostName);
        request.setSampledAt(sampledAt);
        request.setDisks(List.of(samples));
        return request;
    }

    private DiskMonitorSampleEntity sample(String hostId, String device, Instant sampledAt, DiskDetail detail) throws Exception {
        DiskMonitorSampleEntity sample = new DiskMonitorSampleEntity();
        sample.setHostId(hostId);
        sample.setDevice(device);
        sample.setSampledAt(sampledAt);
        sample.setDetailJson(objectMapper.writeValueAsString(detail));
        sample.setRawSmartOutput("raw");
        return sample;
    }

    private DiskDetail detail(String device, String model, String serial, int temp, long readBytes, long writeBytes) {
        return new DiskDetail(device, model, serial, "1 TB", temp, "PASSED", true, true,
                100, 4, readBytes, writeBytes, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                "HDD", List.of(), List.of(), List.of());
    }

    private String smartctlOutput(String model, String serial, int temp, long readLbas, long writtenLbas) {
        return """
                smartctl 7.4
                Device Model:     %s
                Serial Number:    %s
                User Capacity:    1,000,204,886,016 bytes [1.00 TB]
                SMART support is: Available - device has SMART capability.
                SMART support is: Enabled
                SMART overall-health self-assessment test result: PASSED

                Vendor Specific SMART Attributes with Thresholds:
                ID# ATTRIBUTE_NAME          FLAG     VALUE WORST THRESH TYPE      UPDATED  WHEN_FAILED RAW_VALUE
                  9 Power_On_Hours          0x0012   100   100   000    Old_age   Always       -       100
                 12 Power_Cycle_Count       0x0032   100   100   000    Old_age   Always       -       4
                194 Temperature_Celsius     0x0002   176   176   000    Old_age   Always       -       %d
                241 Total_LBAs_Written      0x0012   100   100   000    Old_age   Always       -       %d
                242 Total_LBAs_Read         0x0012   100   100   000    Old_age   Always       -       %d
                """.formatted(model, serial, temp, writtenLbas, readLbas);
    }
}
