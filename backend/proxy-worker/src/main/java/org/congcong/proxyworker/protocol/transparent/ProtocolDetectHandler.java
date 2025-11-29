package org.congcong.proxyworker.protocol.transparent;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.ProxyContext;
import org.congcong.proxyworker.server.netty.ChannelAttributes;

import java.net.InetSocketAddress;
import java.util.List;

@Slf4j
public class ProtocolDetectHandler extends ByteToMessageDecoder {


    // 当前是否已经确定协议类型
    private PayloadProtocolType decidedProtocol;

    // 统一的 sniff 实现
    private final HttpsSniffer httpsSniffer = new HttpsSniffer();
    private final HttpSniffer  httpSniffer  = new HttpSniffer();



    enum PayloadProtocolType {
        HTTPS, HTTP, OTHER;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        InetSocketAddress local = (InetSocketAddress) ctx.channel().localAddress();
        InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
        log.debug("NEW INBOUND: local={} remote={}", local, remote);
        super.channelActive(ctx);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!in.isReadable()) {
            return;
        }

        ProxyContext proxyContext = ChannelAttributes.getProxyContext(ctx.channel());

        // 1. 第一次进来时，先根据前几个字节猜协议类型
        if (decidedProtocol == null) {
            PayloadProtocolType guess = guessProtocol(in);
            switch (guess) {
                case HTTPS:
                case HTTP:
                    decidedProtocol = guess;
                    break;
                case OTHER:
                default:
                    // 没确认是 HTTP/HTTPS，直接走 4 层转发
                    proxyContext.setOriginalTargetHost(proxyContext.getOriginalTargetIP());
                    finishAndForward(ctx, in, out);
                    return;
            }
        }

        // 2. 已经确认是 HTTP 或 HTTPS，需要等待“足够多的字节”供解析
        switch (decidedProtocol) {
            case HTTPS:
                if (!httpsSniffer.trySniff(in, proxyContext)) {
                    // 数据还不够，继续等
                    return;
                }
                break;

            case HTTP:
                if (!httpSniffer.trySniff(in, proxyContext)) {
                    // 数据还不够，继续等
                    return;
                }
                break;

            default:
                // 按理不会走到这里
                //proxyContext.setOriginalTargetHost("unknown");
                break;
        }

        // 3. 能走到这里说明已经 sniff 完成（host 已经写进 ProxyContext）
        finishAndForward(ctx, in, out);
    }

    private void finishAndForward(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // 自己退场
        ctx.pipeline().remove(this);

        // 为什么使用 readRetainedSlice，而不是 in.retain():
        // in.retain() 不会移动 readerIndex，Netty 会认为你“产出了消息却不消费数据”
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

    /**
     * 是否已经收到了完整的 TLS record（基于第一个 record 的 length 字段）
     * 这里只在 looksLikeTls == true 时调用
     */
    private boolean hasCompleteTlsRecord(ByteBuf in) {
        if (in.readableBytes() < 5) {
            return false;
        }
        int idx = in.readerIndex();
        int recordLen = in.getUnsignedShort(idx + 3); // 第一个 TLS record 的长度
        return in.readableBytes() >= 5 + recordLen;
    }

    /**
     * 是否已收到完整的 HTTP 请求头（简化：检测 \r\n\r\n）
     */
    private boolean hasCompleteHttpHeader(ByteBuf in) {
        int start = in.readerIndex();
        int end = in.writerIndex();

        if (end - start < 4) {
            return false;
        }

        for (int i = start; i <= end - 4; i++) {
            if (in.getByte(i)     == '\r' &&
                    in.getByte(i + 1) == '\n' &&
                    in.getByte(i + 2) == '\r' &&
                    in.getByte(i + 3) == '\n') {
                return true;
            }
        }
        return false;
    }

    /**
     * 找到 HTTP 头部的结束位置（\r\n\r\n 后面的 index）
     */
    private int findHttpHeaderEnd(ByteBuf in) {
        int start = in.readerIndex();
        int end = in.writerIndex();

        for (int i = start; i <= end - 4; i++) {
            if (in.getByte(i)     == '\r' &&
                    in.getByte(i + 1) == '\n' &&
                    in.getByte(i + 2) == '\r' &&
                    in.getByte(i + 3) == '\n') {
                return i + 4;
            }
        }
        return -1;
    }

    /**
     * 从 HTTP 头部中解析 Host
     */
    private String extractHttpHost(ByteBuf in) {
        int headerEnd = findHttpHeaderEnd(in);
        if (headerEnd == -1) {
            return null;
        }

        int start = in.readerIndex();
        int length = headerEnd - start;
        String header = in.toString(start, length, java.nio.charset.StandardCharsets.US_ASCII);

        String[] lines = header.split("\r\n");
        for (String line : lines) {
            if (line.regionMatches(true, 0, "Host:", 0, 5)) {
                String host = line.substring(5).trim();
                // 去掉可能的端口
                int colonIndex = host.indexOf(':');
                if (colonIndex > 0) {
                    host = host.substring(0, colonIndex);
                }
                return host;
            }
        }
        return null;
    }

    /**
     * 从 TLS ClientHello 中解析 SNI（保持 readerIndex 不变）
     */
    private String extractSniHostFromClientHello(ByteBuf in) {
        int readerIndex = in.readerIndex();

        if (in.readableBytes() < 5) {
            return null;
        }

        int pos = readerIndex;

        int contentType = in.getUnsignedByte(pos);         // 0x16 = handshake
        int major = in.getUnsignedByte(pos + 1);           // 0x03
        int recordLen = in.getUnsignedShort(pos + 3);      // TLS record length

        if (contentType != 0x16 || major != 0x03) {
            return null;
        }

        // 此处理论上 hasCompleteTlsRecord 已经保证 >= 5 + recordLen，这里再防御一次
        if (in.readableBytes() < 5 + recordLen) {
            return null;
        }

        // 跳过 TLS record 头
        pos += 5;

        // Handshake Type
        if (in.getUnsignedByte(pos) != 0x01) { // 1 = ClientHello
            return null;
        }

        // Handshake 长度（3 字节）
        int handshakeLen = (in.getUnsignedByte(pos + 1) << 16)
                | (in.getUnsignedByte(pos + 2) << 8)
                | in.getUnsignedByte(pos + 3);

        pos += 4; // 跳过 handshake 头

        int handshakeEnd = pos + handshakeLen;
        int bufferEnd = readerIndex + 5 + recordLen;
        if (handshakeEnd > bufferEnd) {
            handshakeEnd = bufferEnd;
        }

        // 1. client_version (2 bytes)
        pos += 2;

        // 2. random (32 bytes)
        pos += 32;

        // 3. session_id
        if (pos + 1 > handshakeEnd) return null;
        int sessionIdLen = in.getUnsignedByte(pos);
        pos += 1 + sessionIdLen;

        if (pos + 2 > handshakeEnd) return null;
        // 4. cipher_suites
        int cipherSuitesLen = in.getUnsignedShort(pos);
        pos += 2 + cipherSuitesLen;

        if (pos + 1 > handshakeEnd) return null;
        // 5. compression_methods
        int compMethodsLen = in.getUnsignedByte(pos);
        pos += 1 + compMethodsLen;

        if (pos + 2 > handshakeEnd) return null;
        // 6. extensions length
        int extensionsLen = in.getUnsignedShort(pos);
        pos += 2;

        int extensionsEnd = Math.min(pos + extensionsLen, handshakeEnd);

        // 7. 遍历 extensions，找 type==0 (server_name)
        while (pos + 4 <= extensionsEnd) {
            int extType = in.getUnsignedShort(pos);
            int extLen = in.getUnsignedShort(pos + 2);
            pos += 4;

            if (extType == 0x0000) { // server_name
                if (pos + 2 > extensionsEnd) return null;

                int serverNameListLen = in.getUnsignedShort(pos);
                pos += 2;

                int serverNameListEnd = Math.min(pos + serverNameListLen, extensionsEnd);

                while (pos + 3 <= serverNameListEnd) {
                    int nameType = in.getUnsignedByte(pos);
                    int nameLen = in.getUnsignedShort(pos + 1);
                    pos += 3;

                    if (pos + nameLen > serverNameListEnd) {
                        return null;
                    }

                    if (nameType == 0x00) { // host_name
                        return in.toString(pos, nameLen, java.nio.charset.StandardCharsets.US_ASCII);
                    } else {
                        pos += nameLen;
                    }
                }
                break;
            } else {
                pos += extLen;
            }
        }

        return null;
    }

    // ====================== 内部类：HTTPS / HTTP Sniffer ======================

    private final class HttpsSniffer {

        /**
         * 返回 true 代表 sniff 完成（已写入 host），false 代表数据还不够，继续等
         */
        boolean trySniff(ByteBuf in, ProxyContext proxyContext) {
            if (!hasCompleteTlsRecord(in)) {
                return false;
            }
            String sniHost = extractSniHostFromClientHello(in);
            log.debug("https sniffer try host:{} ", sniHost);
            proxyContext.setOriginalTargetHost(
                    sniHost != null ? sniHost : "unknown"
            );
            return true;
        }
    }

    private final class HttpSniffer {

        /**
         * 返回 true 代表 sniff 完成（已写入 host），false 代表数据还不够，继续等
         */
        boolean trySniff(ByteBuf in, ProxyContext proxyContext) {
            if (!hasCompleteHttpHeader(in)) {
                return false;
            }
            String host = extractHttpHost(in);
            log.debug("http sniffer try host:{} ", host);
            proxyContext.setOriginalTargetHost(
                    host != null ? host : "unknown"
            );
            return true;
        }
    }
}

