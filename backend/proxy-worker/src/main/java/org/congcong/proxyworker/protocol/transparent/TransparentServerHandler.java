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
import org.congcong.proxyworker.server.tunnel.LogicalTargetSource;
import org.congcong.proxyworker.dns.FakeIpRegistry;

/**
 * 透明代理首包处理：拿到首包后构建 ProxyTunnelRequest 并移除自身。
 */
public class TransparentServerHandler extends SimpleChannelInboundHandler<ByteBuf> {


    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf byteBuf) throws Exception {
        ProxyContext proxyContext = ChannelAttributes.getProxyContext(ctx.channel());
        String originalTargetHost = proxyContext.getOriginalTargetHost();
        if (originalTargetHost == null) {
            originalTargetHost = proxyContext.getOriginalTargetIP();
        }
        boolean fakeIp = FakeIpRegistry.isInPool(proxyContext.getOriginalTargetIP());
        if (fakeIp) {
            if (originalTargetHost.equals(proxyContext.getOriginalTargetIP())) {
                ctx.close();
                return;
            }
            try {
                originalTargetHost = FakeIpRegistry.normalizeDomain(originalTargetHost);
            } catch (IllegalArgumentException invalidDomain) {
                ctx.close();
                return;
            }
        }
        LogicalTargetSource source = ctx.channel().attr(ChannelAttributes.LOGICAL_TARGET_SOURCE).get();
        ProxyTunnelRequest tunnelRequest = createRequest(ctx, originalTargetHost, fakeIp, null,
                source == null ? LogicalTargetSource.ORIGINAL_IP : source);
        // retain 一份给 ProxyTunnelRequest，SimpleChannelInboundHandler 会自动释放原引用
        tunnelRequest.setInitialPayload(byteBuf.retainedSlice());
        FakeIpTransparentHandler guard = ctx.pipeline().get(FakeIpTransparentHandler.class);
        if (guard != null) guard.complete();
        ctx.pipeline().remove(this);
        fireRequest(ctx, tunnelRequest);
    }

    static ProxyTunnelRequest createRequest(ChannelHandlerContext ctx, String host, boolean fakeIp,
                                            ByteBuf firstPacket, LogicalTargetSource source) {
        ProxyContext proxyContext = ChannelAttributes.getProxyContext(ctx.channel());
        InboundConfig inboundConfig = ChannelAttributes.getInboundConfig(ctx.channel());
        UserConfig userConfig = FindUser.find(proxyContext.getClientIp(), inboundConfig);
        if (userConfig == null) throw new IllegalStateException("透明代理缺少匹配设备和匿名用户");
        proxyContext.setUserName(userConfig.getUsername());
        proxyContext.setUserId(userConfig.getId());
        ProxyTunnelRequest request = new ProxyTunnelRequest(ProtocolType.TP_PROXY, host,
                fakeIp ? null : proxyContext.getOriginalTargetIP(), proxyContext.getOriginalTargetPort(),
                userConfig, inboundConfig, firstPacket);
        request.setOriginalTargetIp(proxyContext.getOriginalTargetIP());
        request.setLogicalTargetSource(source);
        return request;
    }

    static void fireRequest(ChannelHandlerContext ctx, ProxyTunnelRequest tunnelRequest) {
        ProxyTimeContext proxyTimeContext = ChannelAttributes.getProxyTimeContext(ctx.channel());
        proxyTimeContext.setConnectEndTime(System.currentTimeMillis());
        ctx.fireChannelRead(tunnelRequest);
    }

}
