package org.congcong.proxyworker.protocol.http;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.ProxyTimeContext;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.config.FindUser;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.config.UserQueryService;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

import java.util.Base64;

@Slf4j
@ChannelHandler.Sharable
public class HttpServerHandler extends SimpleChannelInboundHandler<FullHttpRequest>  {


    private HttpServerHandler() {

    }

    public static HttpServerHandler getInstance() {
        return HttpServerHandler.Holder.INSTANCE;
    }

    private static class Holder {
        private static final HttpServerHandler INSTANCE = new HttpServerHandler();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, FullHttpRequest fullHttpRequest) throws Exception {
        // 支持 HTTP CONNECT 握手
        if (fullHttpRequest.method() != HttpMethod.CONNECT) {
            String resp = "HTTP/1.1 405 Method Not Allowed\r\n" +
                    "Connection: close\r\n" +
                    "Content-Length: 0\r\n\r\n";
            channelHandlerContext.writeAndFlush(Unpooled.copiedBuffer(resp, CharsetUtil.US_ASCII));
            channelHandlerContext.close();
            return;
        }

        InboundConfig inboundConfig = ChannelAttributes.getInboundConfig(channelHandlerContext.channel());
        // 解析 Proxy-Authorization: Basic base64(username:password)
        String authHeader = fullHttpRequest.headers().get("Proxy-Authorization");

        if (authHeader == null || !authHeader.toLowerCase().startsWith("basic ")) {
            String resp = "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                    "Proxy-Authenticate: Basic realm=\"NAS Proxy\"\r\n" +
                    "Connection: close\r\n" +
                    "Content-Length: 0\r\n\r\n";
            channelHandlerContext.writeAndFlush(Unpooled.copiedBuffer(resp, CharsetUtil.US_ASCII));
            channelHandlerContext.close();
            return;
        }

        String base64Part = authHeader.substring(6).trim();
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(base64Part), CharsetUtil.UTF_8);
        } catch (IllegalArgumentException e) {
            String resp = "HTTP/1.1 400 Bad Request\r\n" +
                    "Connection: close\r\n" +
                    "Content-Length: 0\r\n\r\n";
            channelHandlerContext.writeAndFlush(Unpooled.copiedBuffer(resp, CharsetUtil.US_ASCII));
            channelHandlerContext.close();
            return;
        }

        int colonIdx = decoded.indexOf(':');
        if (colonIdx <= 0) {
            String resp = "HTTP/1.1 400 Bad Request\r\n" +
                    "Connection: close\r\n" +
                    "Content-Length: 0\r\n\r\n";
            channelHandlerContext.writeAndFlush(Unpooled.copiedBuffer(resp, CharsetUtil.US_ASCII));
            channelHandlerContext.close();
            return;
        }
        String username = decoded.substring(0, colonIdx);
        UserConfig user = FindUser.find(username, inboundConfig);
        String password = decoded.substring(colonIdx + 1);

        boolean ok = user != null && user.getCredential() != null && user.getCredential().equals(password);
        if (!ok) {
            log.warn("failed to authenticate HTTP proxy user: {}", username);
            String resp = "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                    "Proxy-Authenticate: Basic realm=\"NAS Proxy\"\r\n" +
                    "Connection: close\r\n" +
                    "Content-Length: 0\r\n\r\n";
            channelHandlerContext.writeAndFlush(Unpooled.copiedBuffer(resp, CharsetUtil.US_ASCII));
            channelHandlerContext.close();
            return;
        }

        // 解析目标 host:port
        String uri = fullHttpRequest.uri();
        String targetHost;
        int targetPort;
        int spIdx = uri.lastIndexOf(':');
        if (spIdx > 0 && spIdx < uri.length() - 1) {
            targetHost = uri.substring(0, spIdx);
            try {
                targetPort = Integer.parseInt(uri.substring(spIdx + 1));
            } catch (NumberFormatException e) {
                String resp = "HTTP/1.1 400 Bad Request\r\n" +
                        "Connection: close\r\n" +
                        "Content-Length: 0\r\n\r\n";
                channelHandlerContext.writeAndFlush(Unpooled.copiedBuffer(resp, CharsetUtil.US_ASCII));
                channelHandlerContext.close();
                return;
            }
        } else {
            // 未提供端口，默认 443
            targetHost = uri;
            targetPort = 443;
        }

        // 清理 HTTP 解析处理器，进入纯 TCP 隧道阶段
        channelHandlerContext.pipeline().remove(HttpObjectAggregator.class);
        channelHandlerContext.pipeline().remove(HttpRequestDecoder.class);
        channelHandlerContext.pipeline().remove(this);
        // 记录已认证用户到 Channel 属性
        ChannelAttributes.setAuthenticatedUser(channelHandlerContext.channel(), user);
        ProxyContext proxyContext = ChannelAttributes.getProxyContext(channelHandlerContext.channel());
        proxyContext.setUserId(user.getId());
        proxyContext.setUserName(user.getUsername());

        // 构造统一的隧道请求对象
        ProxyTunnelRequest tunnelRequest = new ProxyTunnelRequest(
                ProtocolType.HTTPS_CONNECT,
                targetHost,
                targetPort,
                user,
                inboundConfig,
                null
        );

        ProxyTimeContext proxyTimeContext = ChannelAttributes.getProxyTimeContext(channelHandlerContext.channel());
        proxyTimeContext.setConnectEndTime(System.currentTimeMillis());
        channelHandlerContext.fireChannelRead(tunnelRequest);
    }
}
