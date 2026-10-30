package org.congcong.proxyworker.telemetry;

import org.congcong.common.telemetry.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SessionObservationTest {
    @Test
    void durationsAreIndependentAndRepeatedNotificationsDoNotDoubleCount() {
        var now = new java.util.concurrent.atomic.AtomicLong(1000);
        SessionObservation state = new SessionObservation(ignored -> { }, now::get);
        state.begin(SessionObservation.Stage.CLIENT_HANDSHAKE, 1000);
        state.end(SessionObservation.Stage.CLIENT_HANDSHAKE, 91000);
        state.begin(SessionObservation.Stage.DNS, 101000);
        state.end(SessionObservation.Stage.DNS, 201000);
        state.begin(SessionObservation.Stage.OUTBOUND_TCP, 201000);
        state.end(SessionObservation.Stage.OUTBOUND_TCP, 211000);
        state.begin(SessionObservation.Stage.OUTBOUND_TCP, 300000);
        state.begin(SessionObservation.Stage.OUTBOUND_TCP, 310000);
        state.end(SessionObservation.Stage.OUTBOUND_TCP, 350000);
        state.end(SessionObservation.Stage.OUTBOUND_TCP, 360000);
        state.begin(SessionObservation.Stage.PROXY_HANDSHAKE, 400000);
        now.set(425000);
        state.close();
        assertEquals(90L, state.summary().getClientHandshakeDurationUs());
        assertEquals(100L, state.summary().getDnsDurationUs());
        assertEquals(60L, state.summary().getOutboundTcpDurationUs());
        assertEquals(25L, state.summary().getProxyHandshakeDurationUs());
        assertNull(state.summary().getOutboundTlsDurationUs());
        state.begin(SessionObservation.Stage.DNS, 500000);
        state.end(SessionObservation.Stage.DNS, 600000);
        assertEquals(100L, state.summary().getDnsDurationUs());
    }

    @Test
    void failedAndCancelledStagesRetainMeasuredTimeWithoutInventingUnreachedStages() {
        var now = new java.util.concurrent.atomic.AtomicLong(1000);
        SessionObservation state = new SessionObservation(ignored -> { }, now::get);
        state.beginConnect();
        state.begin(SessionObservation.Stage.PROXY_HANDSHAKE, 10000);
        now.set(80000);
        state.connectFailed("PROXY_HANDSHAKE", "CONNECT_FAILED", false);
        assertEquals(70L, state.summary().getProxyHandshakeDurationUs());
        assertNull(state.summary().getDnsDurationUs());
        SessionObservation cancelled = new SessionObservation(ignored -> { }, now::get);
        cancelled.begin(SessionObservation.Stage.TARGET_RECOVERY, 80000);
        now.set(100000);
        cancelled.close();
        assertEquals(20L, cancelled.summary().getTargetRecoveryDurationUs());
        assertNull(cancelled.summary().getOutboundTcpDurationUs());
    }

    @Test
    void closeWaitsForWritesAndOnlyCountsSuccessfulPayloadOnce() {
        List<TelemetryRecordV1> logs = new ArrayList<>();
        SessionObservation state = new SessionObservation(logs::add);
        state.beginConnect();
        state.connected();
        state.writeStarted();
        state.writeStarted();
        state.writeStarted();
        state.writeCompleted(true, 17, true);
        state.close();
        state.close();
        assertEquals(0, logs.stream().filter(SessionLogV1.class::isInstance).count());
        state.writeCompleted(false, 4096, true);
        state.writeCompleted(false, 8192, false);
        state.close();
        List<SessionLogV1> sessions = logs.stream().filter(SessionLogV1.class::isInstance).map(SessionLogV1.class::cast).toList();
        assertEquals(1, sessions.size());
        var summary = sessions.get(0);
        assertEquals(17L, summary.getUploadBytes());
        assertEquals(4096L, summary.getDownloadBytes());
        assertEquals(ConnectOutcome.CONNECTED, summary.getConnectOutcome());
        assertEquals(SessionOutcome.ERROR_END, summary.getSessionOutcome());
        assertEquals("RELAY", summary.getFailureStage());
        assertEquals("WRITE_ERROR", summary.getErrorCode());
        assertNull(summary.getDnsDurationUs());
    }

    @Test
    void firstFatalErrorSurvivesCloseAndLaterErrors() {
        List<TelemetryRecordV1> logs = new ArrayList<>();
        SessionObservation state = new SessionObservation(logs::add);
        state.error("AUTH", "INVALID_CREDENTIALS");
        state.error("INBOUND", "CHANNEL_CLOSED");
        state.close();
        assertEquals(1, logs.size());
        assertEquals("AUTH", state.summary().getFailureStage());
        assertEquals(ConnectOutcome.NOT_ATTEMPTED, state.summary().getConnectOutcome());
        assertEquals(SessionOutcome.ERROR_END, state.summary().getSessionOutcome());
        assertNull(state.summary().getConnectedAt());
    }

    @Test
    void blockedConnectionIsNotConvertedToNetworkFailureOrSuccess() {
        SessionObservation state = new SessionObservation(ignored -> { });
        state.beginConnect();
        state.connectFailed("ROUTING", "POLICY_BLOCKED", true);
        state.close();
        assertEquals(ConnectOutcome.BLOCKED, state.summary().getConnectOutcome());
        assertEquals(SessionOutcome.BLOCKED, state.summary().getSessionOutcome());
        assertEquals(0L, state.summary().getDownloadBytes());
    }
}
