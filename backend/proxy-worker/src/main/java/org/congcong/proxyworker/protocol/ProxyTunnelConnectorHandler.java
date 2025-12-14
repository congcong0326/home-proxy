package org.congcong.proxyworker.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;
import org.congcong.common.dto.ProxyContext;
import org.congcong.common.dto.ProxyTimeContext;
import org.congcong.proxyworker.audit.AccessLogUtil;
import org.congcong.proxyworker.context.ProxyContextResolver;
import org.congcong.proxyworker.outbound.OutboundConnector;
import org.congcong.proxyworker.outbound.OutboundConnectorFactory;
import org.congcong.proxyworker.server.RelayHandler;
import org.congcong.proxyworker.server.netty.ChannelAttributes;
import org.congcong.proxyworker.server.tunnel.ProxyTunnelRequest;

@ChannelHandler.Sharable
@Slf4j
public class ProxyTunnelConnectorHandler extends SimpleChannelInboundHandler<ProxyTunnelRequest>  {

    private ProxyTunnelConnectorHandler() {

    }

    public static ProxyTunnelConnectorHandler getInstance() {
        return ProxyTunnelConnectorHandler.Holder.INSTANCE;
    }

    private static class Holder {
        private static final ProxyTunnelConnectorHandler INSTANCE = new ProxyTunnelConnectorHandler();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, ProxyTunnelRequest proxyTunnelRequest) throws Exception {
        ProxyTimeContext proxyTimeContext = ProxyContextResolver.resolveProxyTimeContext(channelHandlerContext.channel(), proxyTunnelRequest);
        proxyTimeContext.setConnectTargetStartTime(System.currentTimeMillis());
        Channel inboundChannel = channelHandlerContext.channel();
        ProxyContext proxyContext = ProxyContextResolver.resolveProxyContext(channelHandlerContext.channel(), proxyTunnelRequest);

        // 填下目标的IP与端口信息
        proxyContext.setOriginalTargetHost(proxyTunnelRequest.getTargetHost());
        proxyContext.setOriginalTargetIP(proxyTunnelRequest.getTargetIp());
        proxyContext.setOriginalTargetPort(proxyTunnelRequest.getTargetPort());

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
                ByteBuf payload = proxyTunnelRequest.getInitialPayload();
                if (payload != null && payload.refCnt() > 0) {
                    payload.release();
                    proxyTunnelRequest.setInitialPayload(null);
                }
                // 连接目标服务器失败
                AccessLogUtil.logFailure(channelHandlerContext.channel(), 500, "NETWORK_ERROR", future.cause().getMessage());
                log.warn("{} 连接目标服务器 {} 失败 {}", proxyTunnelRequest.getRouteConfig().getName(), proxyTunnelRequest.getTargetHost(), future.cause().getMessage());
                // 确保释放出站资源
                Channel ch = future.channel();
                if (ch != null && ch.isOpen()) {
                    ch.close();
                }
            }
            RequestAppendHandler appendHandler = channelHandlerContext.pipeline().get(RequestAppendHandler.class);
            if (appendHandler != null) {
                appendHandler.onConnectComplete(channelHandlerContext);
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
            ProtocolStrategy strategy = ProtocolStrategyRegistry.get(proxyTunnelRequest);
            // 需要注意 SOCKS5 在建立连接成功后根据协议会写回成功 DefaultSocks5CommandResponse，失败的时候也有响应返回
            // HTTPS_CONNECT 协议会写回 "HTTP/1.1 200 Connection Established\r\n" +
            //                "Proxy-agent: https://github.com/cong/cong\r\n" +
            //                "\r\n";
            // shadowsock 不需要写回数据，但是可能需要处理 initialPayload
            if (future.isSuccess()) {
                // 连接成功设置中继服务器
                if (strategy.needRelay()) {
                    setRelay(ctx.channel(), outboundChannel);
                    log.debug("设置中继服务器成功");
                }
                // 写回成功
                strategy.onConnectSuccess(ctx, outboundChannel, proxyTunnelRequest);
                log.debug("执行成功回调");
                if (proxyTunnelRequest.getInitialPayload() != null) {
                    log.debug("写入首次负载");
                    ByteBuf initialPayload = proxyTunnelRequest.getInitialPayload();
                    outboundChannel.writeAndFlush(initialPayload).addListener(f -> {
                        if (!f.isSuccess()) {
                            // 写失败也释放
                            if (initialPayload.refCnt() > 0) {
                                initialPayload.release();
                                proxyTunnelRequest.setInitialPayload(null);
                            }
                        } else {
                            proxyTunnelRequest.setInitialPayload(null); // 已被下游消费
                        }
                    });
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


    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        ProxyTunnelRequest req = ChannelAttributes.getProxyTunnelRequest(ctx.channel());
        if (req != null) {
            ByteBuf payload = req.getInitialPayload();
            if (payload != null && payload.refCnt() > 0) {
                payload.release();
                req.setInitialPayload(null);
            }
            ChannelAttributes.removeProxyTunnelRequest(ctx.channel());
        }
        super.channelInactive(ctx);
    }

}
