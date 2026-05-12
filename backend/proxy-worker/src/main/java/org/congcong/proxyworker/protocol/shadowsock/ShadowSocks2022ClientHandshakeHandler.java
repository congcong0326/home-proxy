package org.congcong.proxyworker.protocol.shadowsock;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;
import org.congcong.proxyworker.util.encryption.algorithm.CryptoProcessor;
import org.congcong.proxyworker.util.encryption.algorithm.NonceUtil;
import org.congcong.proxyworker.util.encryption.algorithm.ShadowSocks2022Key;

import java.util.List;

@Slf4j
public class ShadowSocks2022ClientHandshakeHandler extends ChannelInboundHandlerAdapter {

    private final ProxyTunnelRequest proxyTunnelRequest;
    private final Promise<io.netty.channel.Channel> promise;
    private final CryptoProcessor requestCryptoProcessor;
    private final ShadowSocks2022ClientSession session;
    private final String encodedPassword;

    public ShadowSocks2022ClientHandshakeHandler(ProxyTunnelRequest proxyTunnelRequest,
                                                 Promise<io.netty.channel.Channel> promise,
                                                 CryptoProcessor requestCryptoProcessor,
                                                 ShadowSocks2022ClientSession session,
                                                 String encodedPassword) {
        this.proxyTunnelRequest = proxyTunnelRequest;
        this.promise = promise;
        this.requestCryptoProcessor = requestCryptoProcessor;
        this.session = session;
        this.encodedPassword = encodedPassword;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ByteBuf requestBuf = ctx.alloc().buffer();
        try {
            byte[] requestSalt = ShadowSocks2022Support.randomSalt(requestCryptoProcessor.getSaltSize());
            session.setRequestSalt(requestSalt);
            ShadowSocks2022Support.initSessionSubkey(requestCryptoProcessor, requestSalt);
            requestBuf.writeBytes(requestSalt);
            writeIdentityHeaders(requestBuf, requestSalt);

            ByteBuf variableHeader = ctx.alloc().buffer();
            ByteBuf fixedHeader = ctx.alloc().buffer(ShadowSocks2022Support.REQUEST_FIXED_HEADER_SIZE);
            try {
                ShadowSocksAddressCodec.writeAddress(variableHeader, proxyTunnelRequest.getTargetHost(), proxyTunnelRequest.getTargetPort());
                byte[] padding = ShadowSocks2022Support.randomPadding(16, 64);
                variableHeader.writeShort(padding.length);
                variableHeader.writeBytes(padding);

                fixedHeader.writeByte(ShadowSocks2022Support.CLIENT_STREAM_TYPE);
                ShadowSocks2022Support.writeU64BE(fixedHeader, ShadowSocks2022Support.currentUnixTimeSeconds());
                fixedHeader.writeShort(variableHeader.readableBytes());

                byte[] fixedHeaderBytes = new byte[fixedHeader.readableBytes()];
                fixedHeader.readBytes(fixedHeaderBytes);

                byte[] variableHeaderBytes = new byte[variableHeader.readableBytes()];
                variableHeader.readBytes(variableHeaderBytes);

                ShadowSocks2022Support.Counter counter = new ShadowSocks2022Support.Counter(0);
                requestBuf.writeBytes(requestCryptoProcessor.encrypt(fixedHeaderBytes, NonceUtil.generateNonce(counter.next())));
                requestBuf.writeBytes(requestCryptoProcessor.encrypt(variableHeaderBytes, NonceUtil.generateNonce(counter.next())));
            } finally {
                variableHeader.release();
                fixedHeader.release();
            }

            ctx.writeAndFlush(requestBuf).addListener(future -> {
                if (future.isSuccess()) {
                    ctx.pipeline().remove(this);
                    promise.setSuccess(ctx.channel());
                } else {
                    log.error("Failed to send Shadowsocks 2022 request", future.cause());
                    ctx.close();
                    promise.setFailure(future.cause());
                }
            });
        } catch (Exception e) {
            requestBuf.release();
            exceptionCaught(ctx, e);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Shadowsocks 2022 client handshake exception", cause);
        ctx.close();
        if (!promise.isDone()) {
            promise.setFailure(cause);
        }
    }

    private void writeIdentityHeaders(ByteBuf requestBuf, byte[] requestSalt) {
        List<byte[]> keyChain = ShadowSocks2022Key.decodeKeyChain(encodedPassword, requestCryptoProcessor.getKeySize());
        if (keyChain.size() <= 1) {
            return;
        }
        for (int i = 0; i < keyChain.size() - 1; i++) {
            byte[] identitySubkey = ShadowSocks2022Key.deriveIdentitySubkey(keyChain.get(i), requestSalt);
            byte[] identityPlaintext = ShadowSocks2022Key.hashForIdentityHeader(keyChain.get(i + 1));
            requestBuf.writeBytes(ShadowSocks2022Support.encryptIdentityHeader(identitySubkey, identityPlaintext));
        }
    }
}
