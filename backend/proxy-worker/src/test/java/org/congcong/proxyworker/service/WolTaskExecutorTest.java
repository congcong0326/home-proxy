package org.congcong.proxyworker.service;

import org.congcong.common.dto.WorkerTaskDTO;
import org.congcong.common.dto.WorkerTaskResultDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WolTaskExecutorTest {

    @Test
    void executeBuildsMagicPacketForConfiguredBroadcastAddressAndPort() {
        List<SentPacket> sentPackets = new ArrayList<>();
        WolTaskExecutor executor = new WolTaskExecutor(
                (payload, broadcastIp, port) -> sentPackets.add(new SentPacket(payload, broadcastIp, port))
        );
        WorkerTaskDTO task = new WorkerTaskDTO(1002L, "WOL_WAKE", Map.of(
                "deviceName", "NAS",
                "macAddress", "AA:BB:CC:DD:EE:FF",
                "broadcastIp", "192.168.1.255",
                "port", 9
        ));

        WorkerTaskResultDTO result = executor.execute(task);

        assertTrue(result.getSuccess());
        assertEquals(1002L, result.getTaskId());
        assertEquals(1, sentPackets.size());
        SentPacket sent = sentPackets.get(0);
        assertEquals("192.168.1.255", sent.broadcastIp());
        assertEquals(9, sent.port());
        assertEquals(102, sent.payload().length);
        assertArrayEquals(new byte[] {
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
        }, java.util.Arrays.copyOfRange(sent.payload(), 0, 6));
        assertArrayEquals(new byte[] {
                (byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD, (byte) 0xEE, (byte) 0xFF
        }, java.util.Arrays.copyOfRange(sent.payload(), 6, 12));
    }

    private record SentPacket(byte[] payload, String broadcastIp, int port) {
    }
}
