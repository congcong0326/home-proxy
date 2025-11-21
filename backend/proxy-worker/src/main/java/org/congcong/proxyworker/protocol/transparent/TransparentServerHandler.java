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

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

public class TransparentServerHandler extends ByteToMessageDecoder {


    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf byteBuf, List<Object> list) throws Exception {
        ProxyContext proxyContext = ChannelAttributes.getProxyContext(ctx.channel());
        InboundConfig inboundConfig = ChannelAttributes.getInboundConfig(ctx.channel());
        UserConfig userConfig = getUserConfig(ctx, inboundConfig);
        proxyContext.setUserName(userConfig.getUsername());
        proxyContext.setUserId(userConfig.getId());
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

    private UserConfig getUserConfig(ChannelHandlerContext ctx, InboundConfig inboundConfig) {
        InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
        String hostName = remote.getHostName();
        Map<String, UserConfig> deviceIpMapUser = inboundConfig.getDeviceIpMapUser();
        if (deviceIpMapUser != null && deviceIpMapUser.containsKey(hostName)) {
            return deviceIpMapUser.get(hostName);
        }
        UserConfig userConfig = new UserConfig();
        userConfig.setUsername("匿名访问");
        userConfig.setId(0L);
        return userConfig;
    }

}
