package org.congcong.proxyworker.outbound.reality.session;

import org.congcong.proxyworker.outbound.reality.tls.RealityHandshakeEngine;
import org.congcong.proxyworker.outbound.reality.tls.TlsRecord;
import org.congcong.proxyworker.outbound.reality.tls.TlsRecordType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.List;

public final class RealityHandshakeHandler extends SimpleChannelInboundHandler<TlsRecord> {

    private final RealityHandshakeEngine handshakeEngine;
    private boolean handshakeCompleteNotified;

    public RealityHandshakeHandler(RealityHandshakeEngine handshakeEngine) {
        this.handshakeEngine = handshakeEngine;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        for (TlsRecord record : handshakeEngine.start()) {
            ctx.write(record);
        }
        ctx.flush();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TlsRecord msg) {
        if (handshakeEngine.handshakeComplete() && msg.type() == TlsRecordType.APPLICATION_DATA) {
            ctx.fireChannelRead(msg);
            return;
        }
        List<TlsRecord> outbound = handshakeEngine.accept(msg);
        for (TlsRecord record : outbound) {
            ctx.write(record);
        }
        if (!outbound.isEmpty()) {
            ctx.flush();
        }
        if (handshakeEngine.handshakeComplete() && !handshakeCompleteNotified) {
            handshakeCompleteNotified = true;
            ctx.fireUserEventTriggered("HANDSHAKE_COMPLETE");
        }
    }
}
