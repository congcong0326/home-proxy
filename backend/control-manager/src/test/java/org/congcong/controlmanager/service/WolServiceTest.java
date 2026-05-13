package org.congcong.controlmanager.service;

import org.congcong.controlmanager.config.WolConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WolServiceTest {

    @Test
    void sendWolPacketByIdCalculatesDirectedBroadcastFromDeviceIpAndSubnetMask() {
        WolConfigService wolConfigService = mock(WolConfigService.class);
        WolConfig config = wolConfig("192.168.10.42", "255.255.255.0");
        when(wolConfigService.getConfigById(1L)).thenReturn(Optional.of(config));

        List<SentWolPacket> sentPackets = new ArrayList<>();
        WolService wolService = new WolService(wolConfigService,
                (macAddress, broadcastIp, port) -> sentPackets.add(new SentWolPacket(macAddress, broadcastIp, port)));

        String result = wolService.sendWolPacketById(1L);

        assertThat(result).isEqualTo("WOL魔术包发送成功到设备: Windows");
        assertThat(sentPackets).containsExactly(new SentWolPacket("D8:BB:C1:D4:48:BF", "192.168.10.255", 9));
    }

    @Test
    void defaultSubnetMaskKeepsExistingLimitedBroadcastBehavior() {
        WolConfigService wolConfigService = mock(WolConfigService.class);
        WolConfig config = wolConfig("192.168.10.42", "255.255.255.255");
        when(wolConfigService.getConfigById(1L)).thenReturn(Optional.of(config));

        List<SentWolPacket> sentPackets = new ArrayList<>();
        WolService wolService = new WolService(wolConfigService,
                (macAddress, broadcastIp, port) -> sentPackets.add(new SentWolPacket(macAddress, broadcastIp, port)));

        wolService.sendWolPacketById(1L);

        assertThat(sentPackets).containsExactly(new SentWolPacket("D8:BB:C1:D4:48:BF", "255.255.255.255", 9));
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

    private record SentWolPacket(String macAddress, String broadcastIp, int port) {
    }
}
