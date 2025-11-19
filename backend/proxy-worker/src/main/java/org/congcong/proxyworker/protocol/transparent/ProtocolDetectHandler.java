package org.congcong.proxyworker.protocol.transparent;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import org.congcong.common.dto.ProxyContext;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.netty.tls.TlsContextManager;

import java.util.List;

public class ProtocolDetectHandler extends ByteToMessageDecoder {

    private final InboundConfig inboundConfig;

    public ProtocolDetectHandler(InboundConfig inboundConfig) {
        this.inboundConfig = inboundConfig;
    }

    enum PayloadProtocolType {
        HTTPS,HTTP,OTHER;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!in.isReadable()) {
            return;
        }
        // 只在第一次 decode 时执行一次逻辑，执行完就 remove 自己
        PayloadProtocolType guess = guessProtocol(in);
        ProxyContext proxyContext = ChannelAttributes.getProxyContext(ctx.channel());
        switch (guess) {
            case HTTPS:
                // 需要拿 SNI 的话，在这里插入 SniHandler
                SslContext sslContext = TlsContextManager.getInstance().getServerContext(inboundConfig);
                SniHandler sniHandler = new SniHandler((hostname) -> {
                    proxyContext.setOriginalTargetHost(hostname);
                    return sslContext;
                });
                ctx.pipeline().addAfter(ctx.name(), "sni", sniHandler);
                break;
            case HTTP:
                // 插入一个 HTTP Host 嗅探器（只看 Host）
                ctx.pipeline().addAfter(ctx.name(), "httpHostSniff",  HttpHostSniffHandler.getInstance());
                break;
            case OTHER:
            default:
                // 啥也不做，走纯 TCP 转发
                break;
        }
        // 自己退场
        ctx.pipeline().remove(this);

        // 为什么使用readRetainedSlice ， 而不是 ByteBuf firstPacket = in.retain();
        //  in.retain() readerIndex 不动，Netty 会认为你“产出了消息却不消费数据”，埋雷
        ByteBuf firstPacket = in.readRetainedSlice(in.readableBytes());
        out.add(firstPacket);
    }

    private PayloadProtocolType guessProtocol(ByteBuf in) {
        if (looksLikeTls(in)) {
            return PayloadProtocolType.HTTPS;
        }
        if (looksLikeHttp(in)) {
            return PayloadProtocolType.HTTP;
        }
        return PayloadProtocolType.OTHER;
    }

    private boolean looksLikeTls(ByteBuf in) {
        if (in.readableBytes() < 5) {
            return false;
        }
        int idx = in.readerIndex();
        short contentType = in.getUnsignedByte(idx);       // TLS record type
        short major = in.getUnsignedByte(idx + 1);         // major version
        short minor = in.getUnsignedByte(idx + 2);         // minor version

        // 0x16 = handshake，0x14/0x17 等也是 TLS record，但只用最典型的 0x16 + 0x03.x
        return contentType == 0x16 && major == 0x03 && minor <= 0x04;
    }

    private boolean looksLikeHttp(ByteBuf in) {
        int len = Math.min(in.readableBytes(), 8);
        if (len < 3) {
            return false;
        }
        String prefix = in.toString(in.readerIndex(), len, java.nio.charset.StandardCharsets.US_ASCII);

        return prefix.startsWith("GET ") ||
                prefix.startsWith("POST ") ||
                prefix.startsWith("HEAD ") ||
                prefix.startsWith("PUT ") ||
                prefix.startsWith("DELETE ") ||
                prefix.startsWith("OPTIONS ") ||
                prefix.startsWith("CONNECT ");
    }
}
