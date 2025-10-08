package org.congcong.proxyworker.protocol.shadowsock;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.ProxyTimeContext;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;


import java.util.List;

@Slf4j
public class ShadowSocksHandler extends ByteToMessageDecoder {

    //[1-byte type][variable-length host][2-byte port]
    @Override
    protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf, List<Object> list) throws Exception {
        ProxyTimeContext proxyTimeContext = ChannelAttributes.getProxyTimeContext(channelHandlerContext.channel());
        if (proxyTimeContext.getConnectStartTime() != 0L) {
            proxyTimeContext.setConnectStartTime(System.currentTimeMillis());
        }
        // 检查可读字节数是否足够至少包含1字节类型和2字节端口
        if (byteBuf.readableBytes() < 3) {
            return;  // 等待更多数据
        }
        byteBuf.markReaderIndex();  // 标记当前位置以便在数据不足时恢复
        byte type = byteBuf.readByte();
        Socks5AddressType socks5AddressType = Socks5AddressType.valueOf(type);
        String host;
        if (socks5AddressType == Socks5AddressType.IPv4) {
            if (byteBuf.readableBytes() < 4) {
                byteBuf.resetReaderIndex();  // 恢复读取位置
                return;  // 等待更多数据
            }
            byte[] hostBytes = new byte[4];
            byteBuf.readBytes(hostBytes);
            host = (hostBytes[0] & 0xFF) + "." + (hostBytes[1] & 0xFF) + "." +
                    (hostBytes[2] & 0xFF) + "." + (hostBytes[3] & 0xFF);
        } else if (socks5AddressType == Socks5AddressType.DOMAIN) {
            if (byteBuf.readableBytes() < 1) {
                byteBuf.resetReaderIndex();
                return;  // 等待更多数据
            }
            int domainLength = byteBuf.readByte();
            if (byteBuf.readableBytes() < domainLength) {
                byteBuf.resetReaderIndex();
                return;  // 等待更多数据
            }
            byte[] hostBytes = new byte[domainLength];
            byteBuf.readBytes(hostBytes);
            host = new String(hostBytes);
        } else if (socks5AddressType == Socks5AddressType.IPv6) {
            if (byteBuf.readableBytes() < 16) {
                byteBuf.resetReaderIndex();
                return;  // 等待更多数据
            }
            byte[] hostBytes = new byte[16];
            byteBuf.readBytes(hostBytes);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i += 2) {
                sb.append(String.format("%02x%02x", hostBytes[i], hostBytes[i + 1]));
                if (i < 14) sb.append(":");
            }
            host = sb.toString();
        } else {
            throw new IllegalArgumentException("Unknown address type: " + type);
        }

        if (byteBuf.readableBytes() < 2) {
            byteBuf.resetReaderIndex();  // 等待端口数据
            return;
        }
        // 解析端口
        int port = byteBuf.readUnsignedShort();

        // 拷贝剩下的负载数据
        int readableBytes = byteBuf.readableBytes();

        InboundConfig inboundConfig = ChannelAttributes.getInboundConfig(channelHandlerContext.channel());
        UserConfig authedUser = ChannelAttributes.getAuthenticatedUser(channelHandlerContext.channel());
        ProxyContext proxyContext = ChannelAttributes.getProxyContext(channelHandlerContext.channel());
        proxyContext.setUserId(authedUser.getId());
        proxyContext.setUserName(authedUser.getUsername());


        ProxyTunnelRequest tunnelRequest = new ProxyTunnelRequest(
                ProtocolType.SHADOW_SOCKS,
                host,
                port,
                authedUser,
                inboundConfig,
                readableBytes > 0 ? byteBuf.retain() : null
        );
        channelHandlerContext.channel().pipeline().remove(this);
        proxyTimeContext.setConnectEndTime(System.currentTimeMillis());
        list.add(tunnelRequest);
    }




}
