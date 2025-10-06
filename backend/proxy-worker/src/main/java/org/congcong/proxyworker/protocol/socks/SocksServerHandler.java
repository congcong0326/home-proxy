package org.congcong.proxyworker.protocol.socks;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.socksx.SocksMessage;
import io.netty.handler.codec.socksx.v5.*;
import lombok.extern.slf4j.Slf4j;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

import java.util.Map;

@ChannelHandler.Sharable
@Slf4j
public class SocksServerHandler extends SimpleChannelInboundHandler<SocksMessage>  {

    private SocksServerHandler() {}

    private static class Holder {
        private static final SocksServerHandler INSTANCE = new SocksServerHandler();
    }

    public static SocksServerHandler getInstance() {
        return SocksServerHandler.Holder.INSTANCE;
    }


    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, SocksMessage socksMessage) throws Exception {
        InboundConfig inboundConfig = ChannelAttributes.getInboundConfig(channelHandlerContext.channel());
        Map<String, UserConfig> usersMap = inboundConfig.getUsersMap();
        // 第一次请求过来，协商认证方式，目前只支持用户名加密码
        if (socksMessage instanceof Socks5InitialRequest) {
            Socks5InitialRequest req = (Socks5InitialRequest) socksMessage;
            if (req.authMethods().contains(Socks5AuthMethod.PASSWORD)) {
                // 选择用户名/密码认证，并准备解析后续认证请求
                channelHandlerContext.pipeline().addBefore(channelHandlerContext.name(),
                        "socks5PasswordAuthDecoder",
                        new Socks5PasswordAuthRequestDecoder());
                channelHandlerContext.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.PASSWORD));
            } else {
                // 客户端不支持用户名/密码，拒绝
                channelHandlerContext.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.UNACCEPTED))
                        .addListener(ChannelFutureListener.CLOSE);
            }
            return;
        }

        // 校验认证请求（用户名/密码）
        if (socksMessage instanceof Socks5PasswordAuthRequest) {
            Socks5PasswordAuthRequest authReq = (Socks5PasswordAuthRequest) socksMessage;
            String username = authReq.username();
            String password = authReq.password();

            UserConfig user = usersMap != null ? usersMap.get(username) : null;
            boolean ok = user != null && user.getCredential() != null && user.getCredential().equals(password);

            if (ok) {
                // 认证通过，返回成功并加入命令解析器以处理后续CONNECT等请求
                channelHandlerContext.pipeline().addBefore(channelHandlerContext.name(),
                        "socks5CommandReqDecoder",
                        new Socks5CommandRequestDecoder());
                channelHandlerContext.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS));
                // 记录已认证用户到 Channel 属性，供后续隧道建立使用
                ChannelAttributes.setAuthenticatedUser(channelHandlerContext.channel(), user);
            } else {
                // 认证失败，返回失败并关闭连接
                log.warn("failed to authenticate user: {} by password {}", username, password);
                channelHandlerContext.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE))
                        .addListener(ChannelFutureListener.CLOSE);
            }
            return;
        }

        // 认证都通过了，处理命令请求（交由后续handler处理，这里仅透传并记录）
        if (socksMessage instanceof Socks5CommandRequest) {
            Socks5CommandRequest cmdReq = (Socks5CommandRequest) socksMessage;
            log.debug("SOCKS5 command: {} {}:{}", cmdReq.type(), cmdReq.dstAddr(), cmdReq.dstPort());
            // 清理握手相关解码器，后续不再需要解析 SOCKS5 消息，避免误解码应用层数据
            try {               
                if (channelHandlerContext.pipeline().get("socks5PasswordAuthDecoder") != null) {
                    channelHandlerContext.pipeline().remove("socks5PasswordAuthDecoder");
                }
            } catch (Exception ignore) {}
            try {
                if (channelHandlerContext.pipeline().get("socks5CommandReqDecoder") != null) {
                    channelHandlerContext.pipeline().remove("socks5CommandReqDecoder");
                }
            } catch (Exception ignore) {}
            try {
                if (channelHandlerContext.pipeline().get(Socks5InitialRequestDecoder.class) != null) {
                    channelHandlerContext.pipeline().remove(Socks5InitialRequestDecoder.class);
                }
            } catch (Exception ignore) {}
            // 封装为通用隧道请求对象；首包目前为空，后续若存在首包数据由下游处理器填充
            UserConfig authedUser = ChannelAttributes.getAuthenticatedUser(channelHandlerContext.channel());
            ProxyTunnelRequest tunnelRequest = ProxyTunnelRequest.fromSocks5(cmdReq, inboundConfig, authedUser, null);
            channelHandlerContext.fireChannelRead(tunnelRequest);
            // 移除当前处理器，后续仅由隧道与转发处理器接管
            try {
                channelHandlerContext.pipeline().remove(channelHandlerContext.name());
            } catch (Exception ignore) {}
            return;
        }

        // 其他未预期消息类型
        log.warn("Unexpected SOCKS message: {}", socksMessage.getClass().getSimpleName());
        channelHandlerContext.close();
    }
}
