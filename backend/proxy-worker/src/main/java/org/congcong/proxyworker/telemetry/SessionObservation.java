package org.congcong.proxyworker.telemetry;

import org.congcong.common.telemetry.*;
import java.time.Instant;
import java.util.UUID;
import java.util.EnumMap;
import java.util.function.LongSupplier;
import java.util.function.Consumer;

/** Owned by one inbound EventLoop. Closing waits for the successful-write callbacks. */
public final class SessionObservation {
    final SessionLogV1 summary = new SessionLogV1();
    private final Consumer<TelemetryRecordV1> sink;
    public enum Stage {
        CLIENT_HANDSHAKE, TARGET_RECOVERY, DNS, OUTBOUND_TCP, OUTBOUND_TLS, PROXY_HANDSHAKE
    }
    private final LongSupplier clock;
    private final long startedNanos;
    private final EnumMap<Stage, Long> activeStages = new EnumMap<>(Stage.class);
    private long connectNanos;
    private int pendingWrites;
    private boolean closing;
    private boolean finished;

    public SessionObservation(Consumer<TelemetryRecordV1> sink) {
        this(sink, System::nanoTime);
    }
    SessionObservation(Consumer<TelemetryRecordV1> sink, LongSupplier clock) {
        this.clock = clock;
        this.startedNanos = clock.getAsLong();
        this.sink = sink;
        summary.setConnectionId(UUID.randomUUID().toString());
        summary.setRecordId(summary.getConnectionId());
        summary.setStartedAt(Instant.now());
        summary.setConnectOutcome(ConnectOutcome.NOT_ATTEMPTED);
        summary.setSessionOutcome(SessionOutcome.CANCELLED);
        summary.setIdentitySource(IdentitySource.UNKNOWN);
        summary.setUploadBytes(0L);
        summary.setDownloadBytes(0L);
    }

    public SessionLogV1 summary() {
        return summary;
    }

    public boolean isFinished() {
        return finished;
    }

    public void begin(Stage stage, long now) {
        if (!finished && !closing) activeStages.putIfAbsent(stage, now);
    }
    public void end(Stage stage, long now) {
        if (finished) return;
        Long start = activeStages.remove(stage);
        if (start == null) return;
        long us = Math.max(0, (now - start) / 1000);
        switch (stage) {
            case CLIENT_HANDSHAKE -> summary.setClientHandshakeDurationUs(add(summary.getClientHandshakeDurationUs(), us));
            case TARGET_RECOVERY -> summary.setTargetRecoveryDurationUs(add(summary.getTargetRecoveryDurationUs(), us));
            case DNS -> summary.setDnsDurationUs(add(summary.getDnsDurationUs(), us));
            case OUTBOUND_TCP -> summary.setOutboundTcpDurationUs(add(summary.getOutboundTcpDurationUs(), us));
            case OUTBOUND_TLS -> summary.setOutboundTlsDurationUs(add(summary.getOutboundTlsDurationUs(), us));
            case PROXY_HANDSHAKE -> summary.setProxyHandshakeDurationUs(add(summary.getProxyHandshakeDurationUs(), us));
        }
    }

    private static long add(Long value, long us) {
        return (value == null ? 0 : value) + us;
    }

    public void beginConnect() {
        connectNanos = clock.getAsLong();
    }

    public void connected() {
        if (finished || closing) return;
        end(Stage.PROXY_HANDSHAKE, clock.getAsLong());
        summary.setConnectOutcome(ConnectOutcome.CONNECTED);
        summary.setConnectedAt(Instant.now());
        EstablishedConnectionRegistry.getInstance().connected(summary);
        summary.setChannelSetupDurationUs(Math.max(0, (clock.getAsLong() - connectNanos) / 1000));
        if (summary.getErrorCode() == null) summary.setSessionOutcome(SessionOutcome.NORMAL_END);
    }
    public void connectFailed(String stage, String code, boolean blocked) {
        if (finished) return;
        long now = clock.getAsLong();
        for (Stage pending : new Stage[]{Stage.DNS, Stage.OUTBOUND_TCP, Stage.OUTBOUND_TLS, Stage.PROXY_HANDSHAKE}) end(pending, now);
        summary.setConnectOutcome(blocked ? ConnectOutcome.BLOCKED : ConnectOutcome.FAILED);
        summary.setSessionOutcome(blocked ? SessionOutcome.BLOCKED : SessionOutcome.ERROR_END);
        if (connectNanos != 0) summary.setChannelSetupDurationUs(Math.max(0, (clock.getAsLong() - connectNanos) / 1000));
        error(stage, code);
    }
    public void error(String stage, String code) {
        if (finished) return;
        if ("AUTH".equals(stage)) end(Stage.CLIENT_HANDSHAKE, clock.getAsLong());
        if (summary.getErrorCode() == null) {
            summary.setErrorCode(code);
            summary.setFailureStage(stage);
            if (summary.getSessionOutcome() != SessionOutcome.BLOCKED) summary.setSessionOutcome(SessionOutcome.ERROR_END);
            // Free-form exception messages are deliberately omitted: they may contain credentials.
        }
    }
    public void writeStarted() {
        if (!finished) pendingWrites++;
    }

    public void writeCompleted(boolean upload, int bytes, boolean success) {
        if (finished) return;
        if (success && bytes > 0) {
            Instant now = Instant.now();
            if (upload) {
                summary.setUploadBytes(summary.getUploadBytes() + bytes);
            } else {
                summary.setDownloadBytes(summary.getDownloadBytes() + bytes);
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
        long now = clock.getAsLong();
        for (Stage stage : Stage.values()) end(stage, now);
        finishIfReady();
    }
    private void finishIfReady() {
        if (!closing || finished || pendingWrites != 0) return;
        if (summary.getConnectOutcome() == ConnectOutcome.NOT_ATTEMPTED && summary.getErrorCode() == null) {
            summary.setConnectOutcome(ConnectOutcome.CANCELLED);
        }
        long now = clock.getAsLong();
        for (Stage stage : Stage.values()) end(stage, now);
        summary.setEndedAt(Instant.now());
        summary.setOccurredAt(summary.getEndedAt());
        summary.setSessionDurationUs(Math.max(0, (clock.getAsLong() - startedNanos) / 1000));
        TrafficClassifierV1.classify(summary);
        finished = true;
        sink.accept(summary);
    }
}
