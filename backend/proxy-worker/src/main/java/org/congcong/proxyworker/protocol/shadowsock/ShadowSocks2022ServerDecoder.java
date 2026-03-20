package org.congcong.proxyworker.protocol.shadowsock;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.ProxyTimeContext;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.congcong.proxyworker.util.encryption.algorithm.CryptoProcessor;
import org.congcong.proxyworker.util.encryption.algorithm.ShadowSocks2022Key;

import java.util.Arrays;
import java.util.List;

public class ShadowSocks2022ServerDecoder extends ByteToMessageDecoder {

    private final CryptoProcessor cryptoProcessor;
    private final List<byte[]> keyChain;
    private final ShadowSocks2022Support.Counter decryptCounter = new ShadowSocks2022Support.Counter(0);

    private byte[] requestSalt;
    private boolean saltParsed;
    private boolean identityHeadersParsed;
    private boolean sessionKeyInitialized;
    private boolean fixedHeaderParsed;
    private boolean requestDispatched;
    private int variableHeaderLength = -1;
    private int expectedLength = -1;

    public ShadowSocks2022ServerDecoder(CryptoProcessor cryptoProcessor, String encodedPassword) {
        this.cryptoProcessor = cryptoProcessor;
        this.keyChain = ShadowSocks2022Key.decodeKeyChain(encodedPassword, cryptoProcessor.getKeySize());
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!requestDispatched) {
            if (!tryDecodeRequest(ctx, in, out)) {
                return;
            }
        }

        while (tryDecodePayload(ctx, in, out)) {
            // decode all complete chunks available in the current buffer
        }
    }

    private boolean tryDecodeRequest(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!saltParsed) {
            if (in.readableBytes() < cryptoProcessor.getSaltSize()) {
                return false;
            }
            requestSalt = new byte[cryptoProcessor.getSaltSize()];
            in.readBytes(requestSalt);
            ShadowSocks2022Support.ensureSaltUnique(requestSalt);
            ChannelAttributes.setShadowSocks2022RequestSalt(ctx.channel(), requestSalt);
            saltParsed = true;
        }

        if (!identityHeadersParsed) {
            if (!tryDecodeIdentityHeaders(in)) {
                return false;
            }
            identityHeadersParsed = true;
        }

        if (!sessionKeyInitialized) {
            ShadowSocks2022Support.initSessionSubkey(cryptoProcessor, requestSalt);
            sessionKeyInitialized = true;
        }

        if (!fixedHeaderParsed) {
            int fixedHeaderCipherLength = ShadowSocks2022Support.REQUEST_FIXED_HEADER_SIZE + cryptoProcessor.getTagSize();
            if (in.readableBytes() < fixedHeaderCipherLength) {
                return false;
            }
            byte[] encryptedHeader = new byte[fixedHeaderCipherLength];
            in.readBytes(encryptedHeader);
            byte[] header = ShadowSocks2022Support.decrypt(cryptoProcessor, encryptedHeader, decryptCounter);
            validateRequestFixedHeader(header);
            variableHeaderLength = ((header[9] & 0xFF) << 8) | (header[10] & 0xFF);
            fixedHeaderParsed = true;
        }

        if (in.readableBytes() < variableHeaderLength + cryptoProcessor.getTagSize()) {
            return false;
        }

        byte[] encryptedVariableHeader = new byte[variableHeaderLength + cryptoProcessor.getTagSize()];
        in.readBytes(encryptedVariableHeader);
        byte[] variableHeader = ShadowSocks2022Support.decrypt(cryptoProcessor, encryptedVariableHeader, decryptCounter);

        ByteBuf headerBuf = ctx.alloc().buffer(variableHeader.length);
        headerBuf.writeBytes(variableHeader);
        try {
            ShadowSocksAddressCodec.ParsedAddress parsedAddress = ShadowSocksAddressCodec.readAddress(headerBuf);
            int paddingLength = headerBuf.readUnsignedShort();
            if (headerBuf.readableBytes() < paddingLength) {
                throw new IllegalArgumentException("Invalid Shadowsocks 2022 header padding length");
            }
            headerBuf.skipBytes(paddingLength);

            ByteBuf initialPayload = null;
            if (headerBuf.isReadable()) {
                initialPayload = ctx.alloc().buffer(headerBuf.readableBytes());
                initialPayload.writeBytes(headerBuf);
            }

            ProxyTunnelRequest tunnelRequest = buildTunnelRequest(ctx, parsedAddress.host(), parsedAddress.port(), initialPayload);
            requestDispatched = true;
            out.add(tunnelRequest);
            return true;
        } finally {
            headerBuf.release();
        }
    }

    private boolean tryDecodeIdentityHeaders(ByteBuf in) {
        int identityHeaderCount = Math.max(keyChain.size() - 1, 0);
        if (identityHeaderCount == 0) {
            return true;
        }

        int totalLength = identityHeaderCount * ShadowSocks2022Support.IDENTITY_HEADER_SIZE;
        if (in.readableBytes() < totalLength) {
            return false;
        }

        for (int i = 0; i < identityHeaderCount; i++) {
            byte[] encryptedIdentityHeader = new byte[ShadowSocks2022Support.IDENTITY_HEADER_SIZE];
            in.readBytes(encryptedIdentityHeader);

            byte[] identitySubkey = ShadowSocks2022Key.deriveIdentitySubkey(keyChain.get(i), requestSalt);
            byte[] actualIdentityHash = ShadowSocks2022Support.decryptIdentityHeader(identitySubkey, encryptedIdentityHeader);
            byte[] expectedIdentityHash = ShadowSocks2022Key.hashForIdentityHeader(keyChain.get(i + 1));
            if (!Arrays.equals(actualIdentityHash, expectedIdentityHash)) {
                throw new IllegalArgumentException("Invalid Shadowsocks 2022 identity header");
            }
        }

        return true;
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

    private void validateRequestFixedHeader(byte[] header) {
        if (header.length != ShadowSocks2022Support.REQUEST_FIXED_HEADER_SIZE) {
            throw new IllegalArgumentException("Invalid Shadowsocks 2022 request header size");
        }
        if (header[0] != ShadowSocks2022Support.CLIENT_STREAM_TYPE) {
            throw new IllegalArgumentException("Invalid Shadowsocks 2022 request stream type");
        }
        long requestTimestamp = ShadowSocks2022Support.readU64BE(header, 1);
        ShadowSocks2022Support.validateTimestamp(requestTimestamp);
    }

    private ProxyTunnelRequest buildTunnelRequest(ChannelHandlerContext ctx, String host, int port, ByteBuf initialPayload) {
        InboundConfig inboundConfig = ChannelAttributes.getInboundConfig(ctx.channel());
        UserConfig authenticatedUser = ChannelAttributes.getAuthenticatedUser(ctx.channel());
        ProxyContext proxyContext = ChannelAttributes.getProxyContext(ctx.channel());
        proxyContext.setUserId(authenticatedUser.getId());
        proxyContext.setUserName(authenticatedUser.getUsername());

        ProxyTunnelRequest tunnelRequest = new ProxyTunnelRequest(
                ProtocolType.SHADOW_SOCKS,
                host,
                port,
                authenticatedUser,
                inboundConfig,
                initialPayload
        );
        ProxyTimeContext proxyTimeContext = ChannelAttributes.getProxyTimeContext(ctx.channel());
        proxyTimeContext.setConnectEndTime(System.currentTimeMillis());
        return tunnelRequest;
    }
}
