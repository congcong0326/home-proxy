package org.congcong.proxyworker.protocol.transparent;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.ProxyTimeContext;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.config.FindUser;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

/**
 * 透明代理首包处理：拿到首包后构建 ProxyTunnelRequest 并移除自身。
 */
public class TransparentServerHandler extends SimpleChannelInboundHandler<ByteBuf> {


    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf byteBuf) throws Exception {
        ProxyContext proxyContext = ChannelAttributes.getProxyContext(ctx.channel());
        InboundConfig inboundConfig = ChannelAttributes.getInboundConfig(ctx.channel());
        UserConfig userConfig = FindUser.find(proxyContext.getClientIp(), inboundConfig);
        proxyContext.setUserName(userConfig.getUsername());
        proxyContext.setUserId(userConfig.getId());
        String originalTargetHost = proxyContext.getOriginalTargetHost();
        if (originalTargetHost == null) {
            originalTargetHost = proxyContext.getOriginalTargetIP();
        }
        // retain 一份给 ProxyTunnelRequest，SimpleChannelInboundHandler 会自动释放原引用
        ByteBuf firstPacket = byteBuf.retainedSlice();
        ProxyTunnelRequest tunnelRequest = new ProxyTunnelRequest(
                ProtocolType.TP_PROXY,
                originalTargetHost,
                proxyContext.getOriginalTargetIP(),
                proxyContext.getOriginalTargetPort(),
                userConfig,
                inboundConfig,
                firstPacket
        );
        ctx.pipeline().remove(this);
        ProxyTimeContext proxyTimeContext = ChannelAttributes.getProxyTimeContext(ctx.channel());
        proxyTimeContext.setConnectEndTime(System.currentTimeMillis());
        ctx.fireChannelRead(tunnelRequest);
    }

}
