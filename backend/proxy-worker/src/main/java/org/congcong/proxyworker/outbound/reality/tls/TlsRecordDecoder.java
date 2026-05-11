package org.congcong.proxyworker.outbound.reality.tls;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

public final class TlsRecordDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 5) {
            return;
        }

        in.markReaderIndex();
        int type = in.readUnsignedByte();
        int protocolVersion = in.readUnsignedShort();
        int length = in.readUnsignedShort();

        if (in.readableBytes() < length) {
            in.resetReaderIndex();
            return;
        }

        byte[] payload = new byte[length];
        in.readBytes(payload);
        out.add(new TlsRecord(TlsRecordType.fromCode(type), protocolVersion, payload));
    }
}
