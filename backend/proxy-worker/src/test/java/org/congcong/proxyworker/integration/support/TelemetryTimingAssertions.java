package org.congcong.proxyworker.integration.support;

import org.congcong.common.telemetry.SessionLogV1;
import org.congcong.proxyworker.telemetry.TelemetryPublisher;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Additional assertions when protocol compatibility ITs run with the new collector enabled. */
public final class TelemetryTimingAssertions {
    private TelemetryTimingAssertions() { }

    public static void assertOutbound(int targetPort, String protocol, boolean tls, String confirmation) {
        var publisher = TelemetryPublisher.getInstance();
        if (!publisher.enabled()) return;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        SessionLogV1 record = null;
        while (record == null && System.nanoTime() < deadline) {
            var batch = publisher.peek();
            if (batch != null) {
                record = batch.getSessions().stream()
                        .filter(row -> Integer.valueOf(targetPort).equals(row.getLogicalTargetPort())
                                && protocol.equals(row.getOutboundProtocol())).findFirst().orElse(null);
                // The publisher retains one immutable in-flight batch until ACK; drain it like a controller.
                var ack = new org.congcong.common.telemetry.TelemetryAckV1();
                ack.setBatchId(batch.getBatchId()); ack.setPersisted(true);
                publisher.acknowledge(ack);
            }
            if (record == null) {
                try { Thread.sleep(10); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); fail(interrupted); }
            }
        }
        assertNotNull(record, "Expected a completed session with outbound timings");
        assertNotNull(record.getClientHandshakeDurationUs());
        assertNotNull(record.getRouteSelectionDurationUs());
        assertNotNull(record.getOutboundTcpDurationUs());
        assertNotNull(record.getProxyHandshakeDurationUs());
        if (tls) assertNotNull(record.getOutboundTlsDurationUs());
        else assertNull(record.getOutboundTlsDurationUs());
        assertEquals(confirmation, record.getProxyHandshakeOutcome());
        // These fixtures connect to IP upstream nodes; target DNS is performed remotely by Xray.
        assertNull(record.getDnsDurationUs());
    }
}
