package org.congcong.proxyworker.outbound.reality.session;

import org.congcong.proxyworker.outbound.reality.tls.RealityHandshakeEngine;
import org.congcong.proxyworker.outbound.reality.tls.TlsRecord;
import org.congcong.proxyworker.outbound.reality.tls.TlsRecordType;
import org.congcong.proxyworker.outbound.reality.trace.ConnectionTrace;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.List;

public final class RealityHandshakeHandler extends SimpleChannelInboundHandler<TlsRecord> {

    private final RealityHandshakeEngine handshakeEngine;
    private final ConnectionTrace trace;
    private boolean handshakeCompleteNotified;

    public RealityHandshakeHandler(RealityHandshakeEngine handshakeEngine, ConnectionTrace trace) {
        this.handshakeEngine = handshakeEngine;
        this.trace = trace;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        trace.event("tls.client_hello", "sending");
        for (TlsRecord record : handshakeEngine.start()) {
            ctx.write(record);
        }
        ctx.flush();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TlsRecord msg) {
        trace.event("tls.record.inbound", msg.type().name() + " len=" + msg.payload().length);
        trace.event("tls.record.inbound.hex", ByteBufUtil.hexDump(msg.payload()));
        if (handshakeEngine.handshakeComplete() && msg.type() == TlsRecordType.APPLICATION_DATA) {
            ctx.fireChannelRead(msg);
            return;
        }
        List<TlsRecord> outbound = handshakeEngine.accept(msg);
        for (TlsRecord record : outbound) {
            trace.event("tls.record.outbound", record.type().name() + " len=" + record.payload().length);
            ctx.write(record);
        }
        if (!outbound.isEmpty()) {
            ctx.flush();
        }
        if (handshakeEngine.handshakeComplete() && !handshakeCompleteNotified) {
            handshakeCompleteNotified = true;
            trace.event("tls.handshake.complete", handshakeEngine.state().name());
            ctx.fireUserEventTriggered("HANDSHAKE_COMPLETE");
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        trace.event("tls.exception", cause.getClass().getSimpleName() + ":" + cause.getMessage());
        super.exceptionCaught(ctx, cause);
    }
}
