package org.congcong.proxyworker.protocol.shadowsock;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.util.ByteBufSplitter;
import org.congcong.proxyworker.util.encryption.algorithm.CryptoProcessor;
import org.congcong.proxyworker.util.encryption.algorithm.NonceUtil;

import java.util.List;

public class ShadowSocks2022ServerEncoder extends MessageToByteEncoder<ByteBuf> {

    private final CryptoProcessor cryptoProcessor;
    private final ShadowSocks2022Support.Counter encryptCounter = new ShadowSocks2022Support.Counter(0);

    private boolean firstPacket = true;

    public ShadowSocks2022ServerEncoder(CryptoProcessor cryptoProcessor) {
        this.cryptoProcessor = cryptoProcessor;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
        if (!msg.isReadable()) {
            return;
        }

        List<ByteBuf> chunks = ByteBufSplitter.splitByteBuf(msg);
        if (firstPacket) {
            writeFirstPacket(ctx, chunks, out);
            firstPacket = false;
            return;
        }

        for (ByteBuf chunk : chunks) {
            ShadowSocks2022Support.writeEncryptedChunk(chunk, out, cryptoProcessor, encryptCounter);
        }
    }

    private void writeFirstPacket(ChannelHandlerContext ctx, List<ByteBuf> chunks, ByteBuf out) throws Exception {
        byte[] requestSalt = ChannelAttributes.getShadowSocks2022RequestSalt(ctx.channel());
        if (requestSalt == null) {
            throw new IllegalStateException("Missing Shadowsocks 2022 request salt");
        }

        byte[] responseSalt = ShadowSocks2022Support.randomSalt(cryptoProcessor.getSaltSize());
        ShadowSocks2022Support.initSessionSubkey(cryptoProcessor, responseSalt);
        out.writeBytes(responseSalt);

        ByteBuf firstPayloadChunk = chunks.get(0);
        int firstPayloadLength = firstPayloadChunk.readableBytes();

        ByteBuf header = ctx.alloc().buffer(1 + 8 + requestSalt.length + 2);
        try {
            header.writeByte(ShadowSocks2022Support.SERVER_STREAM_TYPE);
            ShadowSocks2022Support.writeU64BE(header, ShadowSocks2022Support.currentUnixTimeSeconds());
            header.writeBytes(requestSalt);
            header.writeShort(firstPayloadLength);

            byte[] headerPlaintext = new byte[header.readableBytes()];
            header.readBytes(headerPlaintext);
            out.writeBytes(cryptoProcessor.encrypt(headerPlaintext, NonceUtil.generateNonce(encryptCounter.next())));

            byte[] payload = new byte[firstPayloadLength];
            firstPayloadChunk.readBytes(payload);
            out.writeBytes(cryptoProcessor.encrypt(payload, NonceUtil.generateNonce(encryptCounter.next())));
        } finally {
            header.release();
        }

        for (int i = 1; i < chunks.size(); i++) {
            ShadowSocks2022Support.writeEncryptedChunk(chunks.get(i), out, cryptoProcessor, encryptCounter);
        }
    }
}
