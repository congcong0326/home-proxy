package org.congcong.proxyworker.telemetry;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.AttributeKey;
import org.congcong.proxyworker.server.RelayHandler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RelayTimingTest {
    @Test
    void relayWritabilityTracksTheCorrectDirectionAndRestoresReading() {
        EmbeddedChannel inbound = new EmbeddedChannel();
        EmbeddedChannel outbound = new EmbeddedChannel();
        SessionObservation state = new SessionObservation(ignored -> { }, false);
        inbound.attr(AttributeKey.<SessionObservation>valueOf("telemetrySessionV1")).set(state);
        state.relayStarted();
        inbound.pipeline().addLast(new RelayHandler(outbound, true));
        outbound.pipeline().addLast(new RelayHandler(inbound, false));
        try {
            outbound.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
            outbound.runPendingTasks();
            assertFalse(inbound.config().isAutoRead());
            outbound.pipeline().fireChannelWritabilityChanged();
            outbound.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
            outbound.runPendingTasks();
            assertTrue(inbound.config().isAutoRead());
            assertNotNull(state.summary().getUploadBackpressureDurationUs());
            assertEquals(0L, state.summary().getDownloadBackpressureDurationUs());
            inbound.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
            inbound.runPendingTasks();
            assertFalse(outbound.config().isAutoRead());
            state.close();
            assertNotNull(state.summary().getDownloadBackpressureDurationUs());
        } finally {
            inbound.finishAndReleaseAll();
            outbound.finishAndReleaseAll();
        }
    }

    @Test
    void realityHandshakeBoundarySeparatesTlsFromLocalProxyRequest() {
        EmbeddedChannel inbound = new EmbeddedChannel();
        EmbeddedChannel outbound = new EmbeddedChannel();
        SessionObservation state = new SessionObservation(ignored -> { }, false);
        inbound.attr(AttributeKey.<SessionObservation>valueOf("telemetrySessionV1")).set(state);
        var timing = new OutboundTimingHandler(inbound, true, true, "LOCAL_REQUEST_SENT");
        outbound.pipeline().addFirst(timing);
        try {
            outbound.pipeline().fireChannelActive();
            assertNull(state.summary().getProxyHandshakeDurationUs());
            timing.tlsComplete();
            assertNotNull(state.summary().getOutboundTlsDurationUs());
            state.connected();
            assertNotNull(state.summary().getProxyHandshakeDurationUs());
        } finally {
            inbound.finishAndReleaseAll();
            outbound.finishAndReleaseAll();
        }
    }
}
