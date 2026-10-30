package org.congcong.proxyworker.telemetry;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.AttributeKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RelayTimingTest {
    @Test
    void realityHandshakeBoundarySeparatesTlsFromLocalProxyRequest() {
        EmbeddedChannel inbound = new EmbeddedChannel();
        EmbeddedChannel outbound = new EmbeddedChannel();
        SessionObservation state = new SessionObservation(ignored -> { });
        inbound.attr(AttributeKey.<SessionObservation>valueOf("telemetrySessionV1")).set(state);
        var timing = new OutboundTimingHandler(inbound, true, true);
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
