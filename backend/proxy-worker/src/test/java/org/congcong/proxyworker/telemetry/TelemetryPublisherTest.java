package org.congcong.proxyworker.telemetry;

import org.congcong.common.telemetry.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryPublisherTest {
    @Test
    void nodeSwitchPausesAllFactsAndRetainsPendingBatchUntilReenabled() throws Exception {
        TelemetryPublisher publisher = publisher(true);
        try {
            publishAllTypes(publisher, "before");
            TelemetryBatchV1 pending = publisher.peek();
            assertEquals(4, pending.recordCount());

            publisher.setLogReportingEnabled(false);
            assertFalse(publisher.enabled());
            assertFalse(publisher.authEnabled());
            assertFalse(publisher.eventsEnabled());
            assertEquals(false, publisher.status().get("enabled"));
            publishAllTypes(publisher, "disabled");
            assertNull(publisher.peek());
            assertEquals(4, publisher.status().get("queueDepth"));

            publisher.setLogReportingEnabled(true);
            TelemetryBatchV1 resumed = publisher.peek();
            assertEquals(pending.getBatchId(), resumed.getBatchId());
            assertEquals(4, resumed.recordCount());
            TelemetryAckV1 ack = new TelemetryAckV1();
            ack.setBatchId(resumed.getBatchId());
            ack.setPersisted(true);
            publisher.acknowledge(ack);
            assertNull(publisher.peek());
            publishAllTypes(publisher, "after");
            assertEquals(4, publisher.peek().recordCount());
        } finally {
            publisher.shutdown();
        }
    }

    @Test
    void missingNodeSettingDefaultsOnButCannotOverrideDeploymentDisable() throws Exception {
        TelemetryPublisher enabled = publisher(true);
        enabled.setLogReportingEnabled(false);
        enabled.setLogReportingEnabled(null);
        assertTrue(enabled.enabled());

        TelemetryPublisher disabled = publisher(false);
        disabled.setLogReportingEnabled(true);
        assertFalse(disabled.enabled());
        publishAllTypes(disabled, "disabled");
        assertNull(disabled.peek());
        assertEquals(0, disabled.status().get("queueDepth"));
    }

    private TelemetryPublisher publisher(boolean enabled) throws Exception {
        String original = System.getProperty("observability.enabled");
        try {
            System.setProperty("observability.enabled", Boolean.toString(enabled));
            var constructor = TelemetryPublisher.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } finally {
            if (original == null) System.clearProperty("observability.enabled");
            else System.setProperty("observability.enabled", original);
        }
    }

    private void publishAllTypes(TelemetryPublisher publisher, String prefix) {
        TelemetryRecordV1[] records = {new SessionLogV1(), new DnsQueryLogV1(),
                new ProxyEventLogV1(), new CredentialAuthLogV1()};
        for (int i = 0; i < records.length; i++) {
            records[i].setRecordId(prefix + "-" + i);
            publisher.publish(records[i]);
        }
    }
}
