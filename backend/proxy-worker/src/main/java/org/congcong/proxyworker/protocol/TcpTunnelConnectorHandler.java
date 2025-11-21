package org.congcong.proxyworker.protocol;

import io.netty.channel.*;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.ProxyTimeContext;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.audit.AccessLogUtil;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.outbound.OutboundConnector;
import org.congcong.proxyworker.outbound.OutboundConnectorFactory;
import org.congcong.proxyworker.server.RelayHandler;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

@ChannelHandler.Sharable
@Slf4j
public class TcpTunnelConnectorHandler extends SimpleChannelInboundHandler<ProxyTunnelRequest>  {

    private TcpTunnelConnectorHandler() {

    }

    public static TcpTunnelConnectorHandler getInstance() {
        return TcpTunnelConnectorHandler.Holder.INSTANCE;
    }

    private static class Holder {
        private static final TcpTunnelConnectorHandler INSTANCE = new TcpTunnelConnectorHandler();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, ProxyTunnelRequest proxyTunnelRequest) throws Exception {
        ProxyTimeContext proxyTimeContext = ChannelAttributes.getProxyTimeContext(channelHandlerContext.channel());
        proxyTimeContext.setConnectTargetStartTime(System.currentTimeMillis());
        Channel inboundChannel = channelHandlerContext.channel();

        // 用统一的 promise 承载成功/失败，交由 getRelayPromise 处理中继与协议响应
        Promise<Channel> relayPromise = getRelayPromise(channelHandlerContext, proxyTunnelRequest);

        // 根据路由策略挑选出站连接器（直连 / 上游代理 / SS 等）
        OutboundConnector connector = OutboundConnectorFactory.create(proxyTunnelRequest);

        // 执行出站连接；出站连接器内部负责在失败时设置 promise.setFailure(...)
        ChannelFuture connect = connector.connect(inboundChannel, proxyTunnelRequest, relayPromise);
        //
        connect.addListener((ChannelFutureListener) future -> {
            // 第一次请求的生命周期结束
            proxyTimeContext.setConnectTargetEndTime(System.currentTimeMillis());
            proxyTunnelRequest.setStatus(ProxyTunnelRequest.Status.finish);
            if (!future.isSuccess()) {
                // 统一失败处理，交由 TcpTunnelConnectorHandler 的 promise listener 写回协议层响应
                relayPromise.setFailure(future.cause());
                if (proxyTunnelRequest.getInitialPayload() != null) {
                    proxyTunnelRequest.getInitialPayload().release();
                }
                // 连接目标服务器失败
                AccessLogUtil.logFailure(channelHandlerContext.channel(), 500, "NETWORK_ERROR", future.cause().getMessage());
                log.warn("连接目标服务器失败" +
                        " {}", future.cause().getMessage());
                // 确保释放出站资源
                Channel ch = future.channel();
                if (ch != null && ch.isOpen()) {
                    ch.close();
                }
            }
        });
        //
        channelHandlerContext.channel().closeFuture().addListener(f -> {
            if (f.isSuccess()) {
                // 只有在正常关闭的情况下才记录成功日志
                AccessLogUtil.logSuccess(channelHandlerContext.channel());
            }
        });
    }

    /**
     * 设置连接目标服务器成功与失败的回调，这里需要处理：
     * 1. 成功场景，则将channel进行绑定，根据协议不同，可能需要给客户端返回成功，也许不需要返回
     * 2. 失败场景，根据协议不同，返回失败
     * @param ctx
     * @param proxyTunnelRequest
     * @return
     */
    protected Promise<Channel> getRelayPromise(ChannelHandlerContext ctx, ProxyTunnelRequest proxyTunnelRequest) {
        Promise<Channel> promise = ctx.executor().newPromise();
        promise.addListener(future -> {
            Channel outboundChannel = (Channel) future.getNow();
            InboundConfig inboundConfig = proxyTunnelRequest.getInboundConfig();
            ProtocolType protocol = inboundConfig.getProtocol();
            ProtocolStrategy strategy = ProtocolStrategyRegistry.get(protocol);
            // 需要注意 SOCKS5 在建立连接成功后根据协议会写回成功 DefaultSocks5CommandResponse，失败的时候也有响应返回
            // HTTPS_CONNECT 协议会写回 "HTTP/1.1 200 Connection Established\r\n" +
            //                "Proxy-agent: https://github.com/cong/cong\r\n" +
            //                "\r\n";
            // shadowsock 不需要写回数据，但是可能需要处理 initialPayload
            if (future.isSuccess()) {
                // 连接成功设置中继服务器
                setRelay(ctx.channel(), outboundChannel);
                log.debug("设置中继服务器成功");
                // 写回成功
                strategy.onConnectSuccess(ctx, outboundChannel, proxyTunnelRequest);
                log.debug("执行成功回调");
                if (proxyTunnelRequest.getInitialPayload() != null) {
                    log.debug("写入首次负载");
                    outboundChannel.writeAndFlush(proxyTunnelRequest.getInitialPayload());
                }
            }
            // 连接失败
            else {
                Throwable cause = future.cause();
                strategy.onConnectFailure(ctx, outboundChannel, proxyTunnelRequest, cause);
                log.debug("因为{}执行失败回调", cause.getMessage());
            }
        });
        return promise;
    }

    protected void setRelay(Channel inboundChannel, Channel outboundChannel) {
        inboundChannel.pipeline().addLast(new RelayHandler(outboundChannel, true));
        outboundChannel.pipeline().addLast(new RelayHandler(inboundChannel, false));
    }


}
