package org.congcong.proxyworker.telemetry;

import org.congcong.common.telemetry.*;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

/** Owned by one inbound EventLoop. Closing waits for the successful-write callbacks. */
public final class SessionObservation {
    final SessionLogV1 summary = new SessionLogV1();
    private final Consumer<TelemetryRecordV1> sink;
    private final long startedNanos = System.nanoTime();
    private long connectNanos;
    private long sequence;
    private int pendingWrites;
    private boolean closing;
    private boolean finished;
    private boolean connectResultEmitted;
    private final boolean eventsEnabled;

    public SessionObservation(Consumer<TelemetryRecordV1> sink, boolean eventsEnabled) {
        this.sink = sink;
        this.eventsEnabled = eventsEnabled;
        summary.setConnectionId(UUID.randomUUID().toString());
        summary.setRecordId(summary.getConnectionId());
        summary.setStartedAt(Instant.now());
        summary.setConnectOutcome(ConnectOutcome.NOT_ATTEMPTED);
        summary.setSessionOutcome(SessionOutcome.CANCELLED);
        summary.setIdentitySource(IdentitySource.UNKNOWN);
        summary.setUploadBytes(0L);
        summary.setDownloadBytes(0L);
        summary.setCloseInitiator("UNKNOWN");
    }
    public SessionLogV1 summary() { return summary; }
    public void beginConnect() { connectNanos = System.nanoTime(); }
    public void connected() {
        if (finished || closing) return;
        summary.setConnectOutcome(ConnectOutcome.CONNECTED);
        summary.setConnectedAt(Instant.now());
        EstablishedConnectionRegistry.getInstance().connected(summary);
        summary.setChannelSetupDurationUs(Math.max(0, (System.nanoTime() - connectNanos) / 1000));
        if (summary.getErrorCode() == null) summary.setSessionOutcome(SessionOutcome.NORMAL_END);
        connectResult();
    }
    public void connectFailed(String stage, String code, boolean blocked) {
        if (finished) return;
        summary.setConnectOutcome(blocked ? ConnectOutcome.BLOCKED : ConnectOutcome.FAILED);
        summary.setSessionOutcome(blocked ? SessionOutcome.BLOCKED : SessionOutcome.ERROR_END);
        if (connectNanos != 0) summary.setChannelSetupDurationUs(Math.max(0, (System.nanoTime() - connectNanos) / 1000));
        error(stage, code);
        connectResult();
    }
    private void connectResult() {
        if (!connectResultEmitted) {
            connectResultEmitted = true;
            event("CONNECT_RESULT", "CHANNEL_SETUP", summary.getConnectOutcome().name(), summary.getErrorCode());
        }
    }
    public void error(String stage, String code) {
        if (finished) return;
        if (summary.getErrorCode() == null) {
            summary.setErrorCode(code);
            summary.setFailureStage(stage);
            if (summary.getSessionOutcome() != SessionOutcome.BLOCKED) summary.setSessionOutcome(SessionOutcome.ERROR_END);
            // Free-form exception messages are deliberately omitted: they may contain credentials.
            event("FIRST_ERROR", stage, "ERROR", code);
        }
    }
    public void event(String type, String stage, String outcome, String code) {
        if (!eventsEnabled || finished) return;
        ProxyEventLogV1 event = new ProxyEventLogV1();
        event.setRecordId(UUID.randomUUID().toString());
        event.setEventId(event.getRecordId());
        event.setConnectionId(summary.getConnectionId());
        event.setOccurredAt(Instant.now());
        event.setInboundId(summary.getInboundId());
        event.setInboundName(summary.getInboundName());
        event.setConfigVersion(summary.getConfigVersion());
        event.setEventSequence(++sequence);
        event.setEventType(type);
        event.setStage(stage);
        event.setOutcome(outcome);
        event.setErrorCode(code);
        sink.accept(event);
    }
    public void writeStarted() { if (!finished) pendingWrites++; }
    public void writeCompleted(boolean upload, int bytes, boolean success) {
        if (finished) return;
        if (success && bytes > 0) {
            Instant now = Instant.now();
            if (upload) {
                summary.setUploadBytes(summary.getUploadBytes() + bytes);
                if (summary.getFirstUploadAt() == null) summary.setFirstUploadAt(now);
            } else {
                summary.setDownloadBytes(summary.getDownloadBytes() + bytes);
                if (summary.getFirstDownloadAt() == null) summary.setFirstDownloadAt(now);
            }
            summary.setLastActivityAt(now);
        } else if (!success) error("RELAY", "WRITE_ERROR");
        pendingWrites = Math.max(0, pendingWrites - 1);
        finishIfReady();
    }
    public void close() {
        if (closing || finished) return;
        closing = true;
        EstablishedConnectionRegistry.getInstance().closed(summary.getConnectionId());
        finishIfReady();
    }
    private void finishIfReady() {
        if (!closing || finished || pendingWrites != 0) return;
        if (summary.getConnectOutcome() == ConnectOutcome.NOT_ATTEMPTED && summary.getErrorCode() == null) {
            summary.setConnectOutcome(ConnectOutcome.CANCELLED);
        }
        summary.setCloseReason(summary.getErrorCode() == null ? "CHANNEL_CLOSED" : summary.getErrorCode());
        summary.setEndedAt(Instant.now());
        summary.setOccurredAt(summary.getEndedAt());
        summary.setSessionDurationUs(Math.max(0, (System.nanoTime() - startedNanos) / 1000));
        TrafficClassifierV1.classify(summary);
        if (summary.getErrorCode() != null) event("ERROR_SUMMARY", summary.getFailureStage(), summary.getSessionOutcome().name(), summary.getErrorCode());
        finished = true;
        sink.accept(summary);
    }
}
