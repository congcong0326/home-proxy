package org.congcong.proxyworker.telemetry;

import org.congcong.common.telemetry.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SessionObservationTest {
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
