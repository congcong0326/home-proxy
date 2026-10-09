package org.congcong.proxyworker.telemetry;

import org.congcong.common.telemetry.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SessionObservationTest {
    @Test
    void diagnosticEventsCarryTheKnownConnectionContext() {
        List<TelemetryRecordV1> logs = new ArrayList<>();
        SessionObservation state = new SessionObservation(logs::add, true);
        var context = state.summary();
        context.setInboundId(12L);
        context.setInboundName("socks-inbound");
        context.setInboundProtocol("SOCKS5");
        context.setConfigVersion("config-v1");
        context.setRuleSetVersion("rules-v1");
        context.setUserId(9L);
        context.setUsername("alice");
        context.setIdentitySource(IdentitySource.ACCOUNT_AUTH);
        context.setClientIp("192.0.2.7");
        context.setClientPort(54321);
        context.setClientIpSource("TRANSPORT_PEER");
        context.setTransportPeerIp("192.0.2.7");
        context.setTransportPeerPort(54321);
        state.event("TARGET_RECOVERY_RESULT", "TARGET_RECOVERY", "RECOVERED", null);
        state.beginConnect();
        state.connected();
        state.error("RELAY", "WRITE_ERROR");
        state.close();

        var events = logs.stream().filter(ProxyEventLogV1.class::isInstance).map(ProxyEventLogV1.class::cast).toList();
        assertEquals(List.of("TARGET_RECOVERY_RESULT", "CONNECT_RESULT", "FIRST_ERROR", "ERROR_SUMMARY"),
                events.stream().map(ProxyEventLogV1::getEventType).toList());
        for (var event : events) {
            assertEquals(context.getConnectionId(), event.getConnectionId());
            assertEquals(12L, event.getInboundId());
            assertEquals("socks-inbound", event.getInboundName());
            assertEquals("SOCKS5", event.getInboundProtocol());
            assertEquals("config-v1", event.getConfigVersion());
            assertEquals("rules-v1", event.getRuleSetVersion());
            assertEquals(9L, event.getUserId());
            assertEquals("alice", event.getUsername());
            assertEquals(IdentitySource.ACCOUNT_AUTH, event.getIdentitySource());
            assertEquals("192.0.2.7", event.getClientIp());
            assertEquals(54321, event.getClientPort());
            assertEquals("TRANSPORT_PEER", event.getClientIpSource());
            assertEquals("192.0.2.7", event.getTransportPeerIp());
            assertEquals(54321, event.getTransportPeerPort());
        }
    }

    @Test
    void earlyEventsKeepUnknownIdentityWhenTheUserIsIdentifiedLater() {
        List<TelemetryRecordV1> logs = new ArrayList<>();
        SessionObservation state = new SessionObservation(logs::add, true);
        state.summary().setInboundProtocol("SOCKS5");
        state.summary().setClientIp("192.0.2.7");
        state.event("FIRST_ERROR", "AUTH", "ERROR", "INVALID_CREDENTIALS");
        state.summary().setUserId(9L);
        state.summary().setUsername("alice");
        state.summary().setIdentitySource(IdentitySource.ACCOUNT_AUTH);
        state.event("CONNECT_RESULT", "CHANNEL_SETUP", "CONNECTED", null);

        var early = (ProxyEventLogV1) logs.get(0);
        assertNull(early.getUserId());
        assertNull(early.getUsername());
        assertEquals(IdentitySource.UNKNOWN, early.getIdentitySource());
        assertEquals("SOCKS5", early.getInboundProtocol());
        assertEquals("192.0.2.7", early.getClientIp());
        var later = (ProxyEventLogV1) logs.get(1);
        assertEquals("alice", later.getUsername());
        assertEquals(IdentitySource.ACCOUNT_AUTH, later.getIdentitySource());
    }

    @Test
    void durationsAreIndependentAndRepeatedBackpressureNotificationsDoNotDoubleCount() {
        var now = new java.util.concurrent.atomic.AtomicLong(1000);
        SessionObservation state = new SessionObservation(ignored -> { }, false, now::get);
        state.begin(SessionObservation.Stage.CLIENT_HANDSHAKE, 1000);
        state.end(SessionObservation.Stage.CLIENT_HANDSHAKE, 91000);
        state.begin(SessionObservation.Stage.DNS, 101000);
        state.end(SessionObservation.Stage.DNS, 201000);
        state.begin(SessionObservation.Stage.OUTBOUND_TCP, 201000);
        state.end(SessionObservation.Stage.OUTBOUND_TCP, 211000);
        state.relayStarted();
        state.begin(SessionObservation.Stage.UPLOAD_BACKPRESSURE, 300000);
        state.begin(SessionObservation.Stage.UPLOAD_BACKPRESSURE, 310000);
        state.end(SessionObservation.Stage.UPLOAD_BACKPRESSURE, 350000);
        state.end(SessionObservation.Stage.UPLOAD_BACKPRESSURE, 360000);
        state.begin(SessionObservation.Stage.UPLOAD_BACKPRESSURE, 400000);
        now.set(425000);
        state.close();
        assertEquals(90L, state.summary().getClientHandshakeDurationUs());
        assertEquals(100L, state.summary().getDnsDurationUs());
        assertEquals(10L, state.summary().getOutboundTcpDurationUs());
        assertEquals(75L, state.summary().getUploadBackpressureDurationUs());
        assertEquals(0L, state.summary().getDownloadBackpressureDurationUs());
        assertNull(state.summary().getOutboundTlsDurationUs());
        state.begin(SessionObservation.Stage.DNS, 500000);
        state.end(SessionObservation.Stage.DNS, 600000);
        assertEquals(100L, state.summary().getDnsDurationUs());
    }

    @Test
    void failedAndCancelledStagesRetainMeasuredTimeWithoutInventingUnreachedStages() {
        var now = new java.util.concurrent.atomic.AtomicLong(1000);
        SessionObservation state = new SessionObservation(ignored -> { }, false, now::get);
        state.beginConnect();
        state.begin(SessionObservation.Stage.PROXY_HANDSHAKE, 10000);
        now.set(80000);
        state.connectFailed("PROXY_HANDSHAKE", "CONNECT_FAILED", false);
        assertEquals(70L, state.summary().getProxyHandshakeDurationUs());
        assertEquals("FAILED", state.summary().getProxyHandshakeOutcome());
        assertNull(state.summary().getDnsDurationUs());
        SessionObservation cancelled = new SessionObservation(ignored -> { }, false, now::get);
        cancelled.begin(SessionObservation.Stage.TARGET_RECOVERY, 80000);
        now.set(100000);
        cancelled.close();
        assertEquals(20L, cancelled.summary().getTargetRecoveryDurationUs());
        assertNull(cancelled.summary().getOutboundTcpDurationUs());
    }

    @Test
    void closeWaitsForWritesAndOnlyCountsSuccessfulPayloadOnce() {
        List<TelemetryRecordV1> logs = new ArrayList<>();
        SessionObservation state = new SessionObservation(logs::add, true);
        state.beginConnect(); state.connected();
        state.writeStarted(); state.writeStarted(); state.writeStarted();
        state.writeCompleted(true, 17, true);
        state.close(); state.close();
        assertEquals(0, logs.stream().filter(SessionLogV1.class::isInstance).count());
        state.writeCompleted(false, 4096, true);
        state.writeCompleted(false, 8192, false);
        state.close();
        List<SessionLogV1> sessions = logs.stream().filter(SessionLogV1.class::isInstance).map(SessionLogV1.class::cast).toList();
        assertEquals(1, sessions.size());
        var summary = sessions.get(0);
        assertEquals(17L, summary.getUploadBytes()); assertEquals(4096L, summary.getDownloadBytes());
        assertEquals(ConnectOutcome.CONNECTED, summary.getConnectOutcome());
        assertEquals(SessionOutcome.ERROR_END, summary.getSessionOutcome());
        assertEquals("RELAY", summary.getFailureStage()); assertEquals("WRITE_ERROR", summary.getErrorCode());
        assertNull(summary.getDnsDurationUs());
        var events = logs.stream().filter(ProxyEventLogV1.class::isInstance).map(ProxyEventLogV1.class::cast).toList();
        assertEquals(List.of("CONNECT_RESULT", "FIRST_ERROR", "ERROR_SUMMARY"), events.stream().map(ProxyEventLogV1::getEventType).toList());
        assertEquals(List.of(1L, 2L, 3L), events.stream().map(ProxyEventLogV1::getEventSequence).toList());
    }

    @Test
    void firstFatalErrorSurvivesCloseAndLaterErrors() {
        List<TelemetryRecordV1> logs = new ArrayList<>();
        SessionObservation state = new SessionObservation(logs::add, false);
        state.error("AUTH", "INVALID_CREDENTIALS"); state.error("INBOUND", "CHANNEL_CLOSED"); state.close();
        assertEquals(1, logs.size());
        assertEquals("AUTH", state.summary().getFailureStage());
        assertEquals(ConnectOutcome.NOT_ATTEMPTED, state.summary().getConnectOutcome());
        assertEquals(SessionOutcome.ERROR_END, state.summary().getSessionOutcome());
        assertNull(state.summary().getConnectedAt());
    }

    @Test
    void blockedConnectionIsNotConvertedToNetworkFailureOrSuccess() {
        SessionObservation state = new SessionObservation(ignored -> { }, true);
        state.beginConnect(); state.connectFailed("ROUTING", "POLICY_BLOCKED", true); state.close();
        assertEquals(ConnectOutcome.BLOCKED, state.summary().getConnectOutcome());
        assertEquals(SessionOutcome.BLOCKED, state.summary().getSessionOutcome());
        assertEquals(0L, state.summary().getDownloadBytes());
    }
}
