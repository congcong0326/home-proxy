package org.congcong.proxyworker.outbound.reality.tls;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public final class TlsRecordEncoder extends MessageToByteEncoder<TlsRecord> {

    @Override
    protected void encode(ChannelHandlerContext ctx, TlsRecord msg, ByteBuf out) {
        out.writeByte(msg.type().code());
        out.writeShort(msg.protocolVersion());
        out.writeShort(msg.payload().length);
        out.writeBytes(msg.payload());
    }
}
