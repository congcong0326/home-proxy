package org.congcong.proxyworker.protocol.shadowsock;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import lombok.extern.slf4j.Slf4j;
import org.congcong.proxyworker.util.encryption.algorithm.CryptoProcessor;
import org.congcong.proxyworker.util.encryption.algorithm.HKDF;
import org.congcong.proxyworker.util.encryption.algorithm.NonceUtil;

import java.util.List;

/**
 * 解密入站数据
 */
@Slf4j
public class DecryptedSocksHandler extends ByteToMessageDecoder {

    private static final int CHUNK_LENGTH_SIZE = 2;

    private final CryptoProcessor cryptoProcessor;

    private boolean saltParse = false;
    private long decryptCounter = 0;
    private int expectedLength = 0;


    public DecryptedSocksHandler(CryptoProcessor cryptoProcessor) {
        this.cryptoProcessor = cryptoProcessor;
    }

    private void doDecode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        // 获取盐
        int startIndex = in.readerIndex();
        if (!saltParse) {
            if (in.readableBytes() < cryptoProcessor.getSaltSize()) {
                // 本意是防止发送固定字节数的探测，暂时去掉
                //ReplayAttackCheck.init(ctx).setType(Const.BYTE_SALT_ABSENT).setReceiveByte(in.readableBytes()).handleReplayAttackDelay();
                return;
            }
            byte[] salt = new byte[cryptoProcessor.getSaltSize()];
            in.readBytes(salt);
            // 使用HKDF从主密钥和salt派生子密钥
            byte[] subKeyBytes = HKDF.deriveKey(cryptoProcessor.getKey(), salt, cryptoProcessor.getKeySize());
            cryptoProcessor.refreshKey(subKeyBytes);
            saltParse = true;
        }

        // 解密长度字段
        if (expectedLength == 0) {
            if (in.readableBytes() < CHUNK_LENGTH_SIZE + cryptoProcessor.getTagSize()) {
                return;
            }
            byte[] encryptedLength = new byte[CHUNK_LENGTH_SIZE + cryptoProcessor.getTagSize()];
            in.readBytes(encryptedLength);
            // 解密长度
            // 生成递增nonce
            byte[] nonce = NonceUtil.generateNonce(decryptCounter++);
            byte[] lengthBytes = cryptoProcessor.decrypt(encryptedLength, nonce);
            expectedLength = ((lengthBytes[0] & 0xFF) << 8) | (lengthBytes[1] & 0xFF);
        }

        // 解密数据
        if (in.readableBytes() < expectedLength + cryptoProcessor.getTagSize()) {
            return;
        }
        byte[] encryptedPayload = new byte[expectedLength + cryptoProcessor.getTagSize()];
        in.readBytes(encryptedPayload);

        // 解密数据包
        byte[] nonce = NonceUtil.generateNonce(decryptCounter++);
        byte[] decryptedPayload = cryptoProcessor.decrypt(encryptedPayload, nonce);

        // 输出解密后的数据
        ByteBuf outBuf = ctx.alloc().buffer(decryptedPayload.length);
        outBuf.writeBytes(decryptedPayload);
        out.add(outBuf);
        expectedLength = 0;
    }

    // salt
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        doDecode(ctx, in, out);
    }



}
