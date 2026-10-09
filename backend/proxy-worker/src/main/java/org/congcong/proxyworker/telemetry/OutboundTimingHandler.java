package org.congcong.proxyworker.telemetry;

import io.netty.channel.*;
import java.net.SocketAddress;
import static org.congcong.proxyworker.telemetry.SessionObservation.Stage.*;

/** Measures socket connect after address resolution, before protocol handlers start their handshake. */
public final class OutboundTimingHandler extends ChannelDuplexHandler {
    private final Channel inbound;
    private final boolean proxy;
    private final boolean tls;
    private final String readyOutcome;

    public OutboundTimingHandler(Channel inbound, boolean proxy, boolean tls, String readyOutcome) {
        this.inbound = inbound;
        this.proxy = proxy;
        this.tls = tls;
        this.readyOutcome = readyOutcome;
    }

    @Override
    public void connect(ChannelHandlerContext ctx, SocketAddress remote, SocketAddress local, ChannelPromise promise) throws Exception {
        TelemetryHooks.beginStage(inbound, OUTBOUND_TCP);
        promise.addListener(done -> TelemetryHooks.endStage(inbound, OUTBOUND_TCP));
        super.connect(ctx, remote, local, promise);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        TelemetryHooks.endStage(inbound, OUTBOUND_TCP);
        if (tls) TelemetryHooks.beginStage(inbound, OUTBOUND_TLS);
        else if (proxy) TelemetryHooks.beginStage(inbound, PROXY_HANDSHAKE);
        super.channelActive(ctx);
    }

    public void tlsComplete() {
        TelemetryHooks.endStage(inbound, OUTBOUND_TLS);
        if (proxy) TelemetryHooks.beginStage(inbound, PROXY_HANDSHAKE);
    }

    public String readyOutcome() { return readyOutcome; }
}
