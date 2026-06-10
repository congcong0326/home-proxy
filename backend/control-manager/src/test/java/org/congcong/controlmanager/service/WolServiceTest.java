package org.congcong.controlmanager.service;

import org.congcong.controlmanager.config.WolConfig;
import org.congcong.common.dto.WolWakeTaskPayload;
import org.congcong.controlmanager.entity.WorkerTask;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class WolServiceTest {

    @Test
    void sendWolPacketByIdDispatchesWorkerTaskWithDirectedBroadcast() {
        WolConfigService wolConfigService = mock(WolConfigService.class);
        WorkerControlService workerControlService = mock(WorkerControlService.class);
        WolConfig config = wolConfig("192.168.10.42", "255.255.255.0");
        when(wolConfigService.getConfigById(1L)).thenReturn(Optional.of(config));
        when(workerControlService.createTask(eq(WorkerControlService.TASK_TYPE_WOL_WAKE), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new WorkerTask());

        WolService wolService = new WolService(wolConfigService, workerControlService);

        String result = wolService.sendWolPacketById(1L);

        assertThat(result).isEqualTo("WOL唤醒请求已提交: Windows");
        ArgumentCaptor<WolWakeTaskPayload> payloadCaptor = ArgumentCaptor.forClass(WolWakeTaskPayload.class);
        verify(workerControlService).createTask(eq(WorkerControlService.TASK_TYPE_WOL_WAKE), payloadCaptor.capture());
        WolWakeTaskPayload payload = payloadCaptor.getValue();
        assertThat(payload.getMacAddress()).isEqualTo("D8:BB:C1:D4:48:BF");
        assertThat(payload.getBroadcastIp()).isEqualTo("192.168.10.255");
        assertThat(payload.getPort()).isEqualTo(9);
    }

    @Test
    void defaultSubnetMaskDispatchesWorkerTaskWithLimitedBroadcast() {
        WolConfigService wolConfigService = mock(WolConfigService.class);
        WorkerControlService workerControlService = mock(WorkerControlService.class);
        WolConfig config = wolConfig("192.168.10.42", "255.255.255.255");
        when(wolConfigService.getConfigById(1L)).thenReturn(Optional.of(config));
        when(workerControlService.createTask(eq(WorkerControlService.TASK_TYPE_WOL_WAKE), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new WorkerTask());

        WolService wolService = new WolService(wolConfigService, workerControlService);

        wolService.sendWolPacketById(1L);

        ArgumentCaptor<WolWakeTaskPayload> payloadCaptor = ArgumentCaptor.forClass(WolWakeTaskPayload.class);
        verify(workerControlService).createTask(eq(WorkerControlService.TASK_TYPE_WOL_WAKE), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().getBroadcastIp()).isEqualTo("255.255.255.255");
    }

    private static WolConfig wolConfig(String ipAddress, String subnetMask) {
        WolConfig config = new WolConfig();
        config.setId(1L);
        config.setName("Windows");
        config.setIpAddress(ipAddress);
        config.setSubnetMask(subnetMask);
        config.setMacAddress("D8:BB:C1:D4:48:BF");
        config.setWolPort(9);
        config.setStatus(1);
        return config;
    }
}
