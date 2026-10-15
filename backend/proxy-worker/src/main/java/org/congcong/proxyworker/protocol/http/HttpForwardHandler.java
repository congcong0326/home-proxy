package org.congcong.proxyworker.protocol.http;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.config.UserConfig;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 普通 HTTP 正向代理：流式改写一个请求，复用隧道的认证、选路、出站和中继。
 * 每条连接仅承载一个 HTTP 请求，避免跨目标复用绕过按连接进行的选路。
 */
final class HttpForwardHandler extends SimpleChannelInboundHandler<HttpObject> {
    private enum ForwardRequest { INSTANCE }

    private final RequestEncoder encoder = new RequestEncoder();
    private boolean connect;
    private boolean forwarding;
    private boolean requestComplete;

    static boolean isForwardRequest(ProxyTunnelRequest request) {
        return request.getProtocolAttachment() == ForwardRequest.INSTANCE;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpObject message) throws Exception {
        if (connect) {
            ctx.fireChannelRead(ReferenceCountUtil.retain(message));
            return;
        }
        if (requestComplete) {
            // 已声明 Connection: close；不把流水线中的后续请求或凭据送到第一个源站。
            return;
        }
        if (!message.decoderResult().isSuccess()) {
            reject(ctx, HttpResponseStatus.BAD_REQUEST);
            return;
        }
        if (message instanceof HttpRequest request) {
            if (request.method().equals(HttpMethod.CONNECT)) {
                connect = true;
                ctx.fireChannelRead(ReferenceCountUtil.retain(message));
                return;
            }
            UserConfig user = HttpServerHandler.authenticate(ctx, request);
            if (user == null) {
                requestComplete = true;
                return;
            }
            Target target;
            try {
                target = target(request);
                sanitize(request.headers());
            } catch (IllegalArgumentException e) {
                reject(ctx, HttpResponseStatus.BAD_REQUEST);
                return;
            }
            request.setUri(target.path());
            request.headers().set(HttpHeaderNames.HOST, target.authority());
            HttpUtil.setKeepAlive(request, false);

            // 保留 HTTP decoder 处理请求体，移除仅用于 CONNECT 的聚合与握手 handler。
            ctx.pipeline().remove(HttpObjectAggregator.class);
            ctx.pipeline().remove(HttpServerHandler.class);
            ChannelAttributes.setAuthenticatedUser(ctx.channel(), user);
            ProxyContext context = ChannelAttributes.getProxyContext(ctx.channel());
            context.setUserId(user.getId());
            context.setUserName(user.getUsername());
            ChannelAttributes.getProxyTimeContext(ctx.channel()).setConnectEndTime(System.currentTimeMillis());
            forwarding = true;
            // 连接完成前限制额外读取，已有同批数据仍由 RequestAppendHandler 有界缓存。
            ctx.channel().config().setAutoRead(false);
            ctx.fireChannelRead(new ProxyTunnelRequest(ProtocolType.HTTPS_CONNECT,
                    target.host(), target.port(), user,
                    ChannelAttributes.getInboundConfig(ctx.channel()), ForwardRequest.INSTANCE));
        }
        if (forwarding && ctx.channel().isActive()) {
            if (message instanceof LastHttpContent last) {
                // Trailer 同样不能携带代理凭据或逐跳字段。
                sanitize(last.trailingHeaders());
                requestComplete = true;
            }
            encoder.forward(ctx, message);
        }
    }

    private static Target target(HttpRequest request) {
        String value = request.uri();
        URI uri = URI.create(value);
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Fragments are not HTTP request targets");
        }
        boolean absolute = uri.isAbsolute();
        if (absolute) {
            if (!"http".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("Expected http URI");
            }
        } else {
            if (!(value.startsWith("/") && !value.startsWith("//")) && !value.equals("*")) {
                throw new IllegalArgumentException("Expected origin-form URI");
            }
            List<String> hosts = request.headers().getAll(HttpHeaderNames.HOST);
            if (hosts.size() != 1) {
                throw new IllegalArgumentException("Expected one Host header");
            }
            uri = URI.create("http://" + hosts.get(0));
            if ((uri.getRawPath() != null && !uri.getRawPath().isEmpty()) || uri.getRawQuery() != null) {
                throw new IllegalArgumentException("Invalid Host header");
            }
        }
        String host = uri.getHost();
        int port = uri.getPort() == -1 ? 80 : uri.getPort();
        if (host == null || host.isBlank() || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid HTTP target");
        }
        String path = absolute ? uri.getRawPath() : value;
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (absolute && uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }
        String authority = host + (uri.getPort() == -1 ? "" : ":" + port);
        // URI#getHost 保留 IPv6 的方括号，传给 socket resolver 前需要移除。
        if (host.startsWith("[")) {
            host = host.substring(1, host.length() - 1);
        }
        return new Target(host, port, authority, path);
    }

    private static void sanitize(HttpHeaders headers) {
        if (headers.isEmpty()) {
            return;
        }
        for (String connection : headers.getAll(HttpHeaderNames.CONNECTION)) {
            for (String token : connection.split(",")) {
                String name = token.trim().toLowerCase(Locale.ROOT);
                if (name.equals("host") || name.equals("content-length") || name.equals("transfer-encoding")) {
                    throw new IllegalArgumentException("Connection must not nominate framing or routing headers");
                }
                if (!name.isEmpty()) {
                    headers.remove(name);
                }
            }
        }
        headers.remove(HttpHeaderNames.CONNECTION);
        headers.remove(HttpHeaderNames.PROXY_AUTHORIZATION);
        headers.remove(HttpHeaderNames.PROXY_AUTHENTICATE);
        headers.remove("Proxy-Connection");
        headers.remove("Keep-Alive");
        headers.remove(HttpHeaderNames.TE);
        headers.remove(HttpHeaderNames.UPGRADE);
    }

    private void reject(ChannelHandlerContext ctx, HttpResponseStatus status) {
        requestComplete = true;
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private record Target(String host, int port, String authority, String path) { }

    /** 将标准 Netty HTTP encoder 的输出交给现有 ByteBuf 缓存与中继链路。 */
    private static final class RequestEncoder extends HttpRequestEncoder {
        void forward(ChannelHandlerContext ctx, HttpObject message) throws Exception {
            List<Object> output = new ArrayList<>();
            try {
                // HttpObjectEncoder 接管并释放输入；保留 SimpleChannelInboundHandler 的那份引用。
                encode(ctx, ReferenceCountUtil.retain(message), output);
            } catch (Exception e) {
                output.forEach(ReferenceCountUtil::release);
                throw e;
            }
            for (Object encoded : output) {
                ctx.fireChannelRead((ByteBuf) encoded);
            }
        }
    }
}
