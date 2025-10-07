package org.congcong.proxyworker.protocol;

import io.netty.channel.*;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.enums.ProtocolType;
import org.congcong.proxyworker.config.InboundConfig;
import org.congcong.proxyworker.outbound.OutboundConnector;
import org.congcong.proxyworker.outbound.OutboundConnectorFactory;
import org.congcong.proxyworker.server.RelayHandler;
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
        Channel inboundChannel = channelHandlerContext.channel();

        // 用统一的 promise 承载成功/失败，交由 getRelayPromise 处理中继与协议响应
        Promise<Channel> relayPromise = getRelayPromise(channelHandlerContext, proxyTunnelRequest);

        // 根据路由策略挑选出站连接器（直连 / 上游代理 / SS 等）
        OutboundConnector connector = OutboundConnectorFactory.create(proxyTunnelRequest);

        // 执行出站连接；出站连接器内部负责在失败时设置 promise.setFailure(...)
        ChannelFuture connect = connector.connect(inboundChannel, proxyTunnelRequest, relayPromise);
        // 关注失败回调
        connect.addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                // 统一失败处理，交由 TcpTunnelConnectorHandler 的 promise listener 写回协议层响应
                relayPromise.setFailure(future.cause());
                // 确保释放出站资源
                Channel ch = future.channel();
                if (ch != null && ch.isOpen()) {
                    ch.close();
                }
            }
        });
        // 关注成功回调
        ChannelFuture channelFuture = connect.channel().closeFuture().addListener((ChannelFutureListener) future -> {

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
                setRelay(ctx.channel(), outboundChannel, proxyTunnelRequest);
                // 写回成功
                strategy.onConnectSuccess(ctx, outboundChannel, proxyTunnelRequest);
            }
            // 连接失败
            else {
                Throwable cause = future.cause();
                strategy.onConnectFailure(ctx, outboundChannel, proxyTunnelRequest, cause);
            }
        });
        return promise;
    }

    protected void setRelay(Channel inboundChannel, Channel outboundChannel, ProxyTunnelRequest proxyTunnelRequest) {
        inboundChannel.pipeline().addLast(new RelayHandler(outboundChannel, true));
        outboundChannel.pipeline().addLast(new RelayHandler(inboundChannel, false));
        if (proxyTunnelRequest.getInitialPayload() != null) {
            outboundChannel.writeAndFlush(proxyTunnelRequest.getInitialPayload());
        }
    }

}
