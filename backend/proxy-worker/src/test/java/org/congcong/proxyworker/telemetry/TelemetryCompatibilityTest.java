package org.congcong.proxyworker.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.congcong.common.dto.WorkerManagementMessage;
import org.congcong.common.dto.WorkerManagementMessageType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryCompatibilityTest {
    @Test
    void unnegotiatedFramesRemainReadableByStrictLegacyEnvelopeAndEnum() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (WorkerManagementMessageType type : new WorkerManagementMessageType[]{WorkerManagementMessageType.CONFIG_POLL,
                WorkerManagementMessageType.CONFIG_UNCHANGED, WorkerManagementMessageType.CONFIG, WorkerManagementMessageType.WOL_WAKE}) {
            String json = mapper.writeValueAsString(new WorkerManagementMessage(type, "node", "hash", null));
            assertFalse(json.contains("telemetry"));
            OldEnvelope old = mapper.readValue(json, OldEnvelope.class);
            assertEquals(type.name(), old.getType().name());
            assertEquals("node", old.getWorkerId());
        }
    }
    enum OldType { CONFIG_POLL, CONFIG, CONFIG_UNCHANGED, WOL_WAKE }
    @Data
    static class OldEnvelope {
        private OldType type;
        private String workerId;
        private String configHash;
        private Object config;
        private Object heartbeat;
        private Object accessLogs;
        private Object authLogs;
        private Object taskPayload;
    }
}
