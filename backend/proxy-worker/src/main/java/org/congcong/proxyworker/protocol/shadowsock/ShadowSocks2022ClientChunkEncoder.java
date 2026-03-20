package org.congcong.proxyworker.protocol.shadowsock;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.congcong.proxyworker.util.encryption.algorithm.CryptoProcessor;

public class ShadowSocks2022ClientChunkEncoder extends MessageToByteEncoder<ByteBuf> {

    private final CryptoProcessor cryptoProcessor;
    private final ShadowSocks2022Support.Counter encryptCounter = new ShadowSocks2022Support.Counter(2);

    public ShadowSocks2022ClientChunkEncoder(CryptoProcessor cryptoProcessor) {
        this.cryptoProcessor = cryptoProcessor;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
        if (!msg.isReadable()) {
            return;
        }
        ShadowSocks2022Support.writeEncryptedChunks(msg, out, cryptoProcessor, encryptCounter);
    }
}
