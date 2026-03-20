package org.congcong.proxyworker.protocol.shadowsock;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.congcong.proxyworker.util.encryption.algorithm.CryptoProcessor;

import java.util.Arrays;
import java.util.List;

public class ShadowSocks2022ClientResponseDecoder extends ByteToMessageDecoder {

    private final CryptoProcessor cryptoProcessor;
    private final ShadowSocks2022ClientSession session;
    private final ShadowSocks2022Support.Counter decryptCounter = new ShadowSocks2022Support.Counter(0);

    private boolean saltParsed;
    private boolean fixedHeaderParsed;
    private int expectedLength = -1;

    public ShadowSocks2022ClientResponseDecoder(CryptoProcessor cryptoProcessor, ShadowSocks2022ClientSession session) {
        this.cryptoProcessor = cryptoProcessor;
        this.session = session;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!fixedHeaderParsed) {
            if (!tryDecodeResponseHeader(ctx, in, out)) {
                return;
            }
        }

        while (tryDecodePayload(ctx, in, out)) {
            // decode all complete chunks available in the current buffer
        }
    }

    private boolean tryDecodeResponseHeader(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!saltParsed) {
            if (in.readableBytes() < cryptoProcessor.getSaltSize()) {
                return false;
            }
            byte[] responseSalt = new byte[cryptoProcessor.getSaltSize()];
            in.readBytes(responseSalt);
            ShadowSocks2022Support.initSessionSubkey(cryptoProcessor, responseSalt);
            saltParsed = true;
        }

        int plainHeaderLength = 1 + 8 + cryptoProcessor.getSaltSize() + 2;
        int cipherHeaderLength = plainHeaderLength + cryptoProcessor.getTagSize();
        if (in.readableBytes() < cipherHeaderLength) {
            return false;
        }

        byte[] encryptedHeader = new byte[cipherHeaderLength];
        in.readBytes(encryptedHeader);
        byte[] header = ShadowSocks2022Support.decrypt(cryptoProcessor, encryptedHeader, decryptCounter);
        validateResponseFixedHeader(header);
        expectedLength = ((header[header.length - 2] & 0xFF) << 8) | (header[header.length - 1] & 0xFF);
        fixedHeaderParsed = true;
        return tryDecodePayload(ctx, in, out);
    }

    private boolean tryDecodePayload(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (expectedLength < 0) {
            int lengthCipherSize = 2 + cryptoProcessor.getTagSize();
            if (in.readableBytes() < lengthCipherSize) {
                return false;
            }
            byte[] encryptedLength = new byte[lengthCipherSize];
            in.readBytes(encryptedLength);
            byte[] lengthBytes = ShadowSocks2022Support.decrypt(cryptoProcessor, encryptedLength, decryptCounter);
            expectedLength = ((lengthBytes[0] & 0xFF) << 8) | (lengthBytes[1] & 0xFF);
        }

        if (in.readableBytes() < expectedLength + cryptoProcessor.getTagSize()) {
            return false;
        }

        byte[] encryptedPayload = new byte[expectedLength + cryptoProcessor.getTagSize()];
        in.readBytes(encryptedPayload);
        byte[] payload = ShadowSocks2022Support.decrypt(cryptoProcessor, encryptedPayload, decryptCounter);
        ByteBuf payloadBuf = ctx.alloc().buffer(payload.length);
        payloadBuf.writeBytes(payload);
        out.add(payloadBuf);
        expectedLength = -1;
        return true;
    }

    private void validateResponseFixedHeader(byte[] header) {
        if (header[0] != ShadowSocks2022Support.SERVER_STREAM_TYPE) {
            throw new IllegalArgumentException("Invalid Shadowsocks 2022 response stream type");
        }
        long responseTimestamp = ShadowSocks2022Support.readU64BE(header, 1);
        ShadowSocks2022Support.validateTimestamp(responseTimestamp);
        byte[] expectedRequestSalt = session.getRequestSalt();
        if (expectedRequestSalt == null) {
            throw new IllegalStateException("Missing Shadowsocks 2022 request salt");
        }

        int requestSaltOffset = 1 + 8;
        byte[] actualRequestSalt = Arrays.copyOfRange(header, requestSaltOffset, requestSaltOffset + expectedRequestSalt.length);
        if (!Arrays.equals(expectedRequestSalt, actualRequestSalt)) {
            throw new IllegalArgumentException("Shadowsocks 2022 response salt mismatch");
        }
    }
}
