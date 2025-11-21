package org.congcong.proxyworker.protocol.transparent;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.ProxyTimeContext;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

import java.util.List;

public class TransparentServerHandler extends ByteToMessageDecoder {


    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf byteBuf, List<Object> list) throws Exception {
        ProxyContext proxyContext = ChannelAttributes.getProxyContext(ctx.channel());
        InboundConfig inboundConfig = ChannelAttributes.getInboundConfig(ctx.channel());
        UserConfig userConfig = new UserConfig();
        userConfig.setUsername("匿名访问");
        userConfig.setId(0L);
        proxyContext.setUserName("匿名访问");
        proxyContext.setUserId(0L);


        int len = byteBuf.readableBytes();
        ByteBuf firstPacket = byteBuf.readRetainedSlice(len);
        ProxyTunnelRequest tunnelRequest = new ProxyTunnelRequest(
                ProtocolType.TP_PROXY,
                proxyContext.getOriginalTargetIP(),
                proxyContext.getOriginalTargetPort(),
                userConfig,
                inboundConfig,
                firstPacket
        );
        ctx.channel().pipeline().remove(this);
        ProxyTimeContext proxyTimeContext = ChannelAttributes.getProxyTimeContext(ctx.channel());
        proxyTimeContext.setConnectEndTime(System.currentTimeMillis());
        list.add(tunnelRequest);
    }
}
